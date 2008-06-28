/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.core.singleton;

import java.lang.reflect.Method;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import javax.ejb.SessionBean;
import javax.ejb.SessionContext;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.transaction.TransactionManager;
import javax.xml.ws.WebServiceContext;

import org.apache.openejb.Injection;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.SystemException;
import org.apache.openejb.core.BaseContext;
import org.apache.openejb.core.CoreDeploymentInfo;
import org.apache.openejb.core.Operation;
import org.apache.openejb.core.ThreadContext;
import org.apache.openejb.core.interceptor.InterceptorData;
import org.apache.openejb.core.interceptor.InterceptorStack;
import org.apache.openejb.spi.SecurityService;
import org.apache.openejb.util.LinkedListStack;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.SafeToolkit;
import org.apache.openejb.util.Stack;
import org.apache.xbean.recipe.ConstructionException;
import org.apache.xbean.recipe.ObjectRecipe;
import org.apache.xbean.recipe.Option;
import org.apache.xbean.recipe.StaticRecipe;

public class SingletonInstanceManager {
    private static final Logger logger = Logger.getInstance(LogCategory.OPENEJB, "org.apache.openejb.util.resources");

    protected int poolLimit = 0;
    protected int beanCount = 0;
    protected boolean strictPooling = false;

    protected HashMap<Object,Semaphore> semaphores;

    protected final SafeToolkit toolkit = SafeToolkit.getToolkit("SingletonInstanceManager");
    private TransactionManager transactionManager;
    private SecurityService securityService;

    public SingletonInstanceManager(TransactionManager transactionManager, SecurityService securityService) {
        this.transactionManager = transactionManager;
        this.securityService = securityService;
        this.poolLimit = 1;
        this.strictPooling = true;

        if (this.strictPooling) {
            this.semaphores = new HashMap();
        }
    }

    /**
     * Removes an instance from the pool and returns it for use
     * by the container in business methods.
     *
     * If the pool is at it's limit the StrictPooling flag will
     * cause this thread to wait.
     *
     * If StrictPooling is not enabled this method will create a
     * new stateless bean instance performing all required injection
     * and callbacks before returning it in a method ready state.
     * 
     * @param callContext
     * @return
     * @throws OpenEJBException
     */
    public Object getInstance(ThreadContext callContext)
            throws OpenEJBException {
        CoreDeploymentInfo deploymentInfo = callContext.getDeploymentInfo();
        Data data = (Data) deploymentInfo.getContainerData();
        Stack pool = data.getPool();
        if(strictPooling){
            try {
                semaphores.get(deploymentInfo.getDeploymentID()).acquire();
            } catch (InterruptedException e2) {
                throw new OpenEJBException("Unexpected Interruption of current thread: ",e2);
            }
        }
        Object bean = pool.pop();

        if (bean == null) {

            Class beanClass = deploymentInfo.getBeanClass();
            ObjectRecipe objectRecipe = new ObjectRecipe(beanClass);
            objectRecipe.allow(Option.FIELD_INJECTION);
            objectRecipe.allow(Option.PRIVATE_PROPERTIES);
            objectRecipe.allow(Option.IGNORE_MISSING_PROPERTIES);
            objectRecipe.allow(Option.NAMED_PARAMETERS);

            Operation originalOperation = callContext.getCurrentOperation();
            BaseContext.State[] originalAllowedStates = callContext.getCurrentAllowedStates();

            try {
                Context ctx = deploymentInfo.getJndiEnc();                
                SessionContext sessionContext;
                // This needs to be synchronized as this code is multi-threaded.
                // In between the lookup and the bind a bind may take place in another Thread.
                // This is a fix for GERONIMO-3444
                synchronized(this){
                    try {                    
                        sessionContext = (SessionContext) ctx.lookup("java:comp/EJBContext");
                    } catch (NamingException e1) {
                        sessionContext = createSessionContext();
                        // TODO: This should work
                        ctx.bind("java:comp/EJBContext", sessionContext);
                    }                  
                }
                if (javax.ejb.SessionBean.class.isAssignableFrom(beanClass) || hasSetSessionContext(beanClass)) {
                    callContext.setCurrentOperation(Operation.INJECTION);
                    callContext.setCurrentAllowedStates(SingletonContext.getStates());
                    objectRecipe.setProperty("sessionContext", new StaticRecipe(sessionContext));
                }     
                
                WebServiceContext wsContext;
                // This is a fix for GERONIMO-3444
                synchronized(this){
                    try {
                        wsContext = (WebServiceContext) ctx.lookup("java:comp/WebServiceContext");
                    } catch (NamingException e) {
                        wsContext = new EjbWsContext(sessionContext);
                        ctx.bind("java:comp/WebServiceContext", wsContext);
                    }
                }

                fillInjectionProperties(objectRecipe, beanClass, deploymentInfo, ctx);

                bean = objectRecipe.create(beanClass.getClassLoader());
                Map unsetProperties = objectRecipe.getUnsetProperties();
                if (unsetProperties.size() > 0) {
                    for (Object property : unsetProperties.keySet()) {
                        logger.warning("Injection: No such property '" + property + "' in class " + beanClass.getName());
                    }
                }

                HashMap<String, Object> interceptorInstances = new HashMap<String, Object>();
                for (InterceptorData interceptorData : deploymentInfo.getAllInterceptors()) {
                    if (interceptorData.getInterceptorClass().equals(beanClass)) continue;

                    Class clazz = interceptorData.getInterceptorClass();
                    ObjectRecipe interceptorRecipe = new ObjectRecipe(clazz);
                    interceptorRecipe.allow(Option.FIELD_INJECTION);
                    interceptorRecipe.allow(Option.PRIVATE_PROPERTIES);
                    interceptorRecipe.allow(Option.IGNORE_MISSING_PROPERTIES);
                    interceptorRecipe.allow(Option.NAMED_PARAMETERS);

                    fillInjectionProperties(interceptorRecipe, clazz, deploymentInfo, ctx);

                    try {
                        Object interceptorInstance = interceptorRecipe.create(clazz.getClassLoader());
                        interceptorInstances.put(clazz.getName(), interceptorInstance);
                    } catch (ConstructionException e) {
                        throw new Exception("Failed to create interceptor: " + clazz.getName(), e);
                    }
                }

                interceptorInstances.put(beanClass.getName(), bean);


                try {
                    callContext.setCurrentOperation(Operation.POST_CONSTRUCT);
                    callContext.setCurrentAllowedStates(SingletonContext.getStates());

                    List<InterceptorData> callbackInterceptors = deploymentInfo.getCallbackInterceptors();
                    InterceptorStack interceptorStack = new InterceptorStack(bean, null, Operation.POST_CONSTRUCT, callbackInterceptors, interceptorInstances);
                    interceptorStack.invoke();
                } catch (Exception e) {
                    throw e;
                }

                try {
                    if (bean instanceof SessionBean){
                        callContext.setCurrentOperation(Operation.CREATE);
                        callContext.setCurrentAllowedStates(SingletonContext.getStates());
                        Method create = deploymentInfo.getCreateMethod();
                        InterceptorStack interceptorStack = new InterceptorStack(bean, create, Operation.CREATE, new ArrayList<InterceptorData>(), new HashMap());
                        interceptorStack.invoke();
                    }
                } catch (Exception e) {
                    throw e;
                }

                bean = new Instance(bean, interceptorInstances);
            } catch (Throwable e) {
                if (e instanceof java.lang.reflect.InvocationTargetException) {
                    e = ((java.lang.reflect.InvocationTargetException) e).getTargetException();
                }
                String t = "The bean instance " + bean + " threw a system exception:" + e;
                logger.error(t, e);
                throw new org.apache.openejb.ApplicationException(new RemoteException("Cannot obtain a free instance.", e));
            } finally {
                callContext.setCurrentOperation(originalOperation);
                callContext.setCurrentAllowedStates(originalAllowedStates);
            }
        }
        return bean;
    }

    private static void fillInjectionProperties(ObjectRecipe objectRecipe, Class clazz, CoreDeploymentInfo deploymentInfo, Context context) {
        boolean usePrefix = true;

        try {
            clazz.getConstructor();
        } catch (NoSuchMethodException e) {
            // Using constructor injection
            // xbean can't handle the prefix yet
            usePrefix = false;
        }

        for (Injection injection : deploymentInfo.getInjections()) {
            if (!injection.getTarget().isAssignableFrom(clazz)) continue;
            try {
                String jndiName = injection.getJndiName();
                Object object = context.lookup("java:comp/env/" + jndiName);
                String prefix;
                if (usePrefix) {
                    prefix = injection.getTarget().getName() + "/";
                } else {
                    prefix = "";
                }

                if (object instanceof String) {
                    String string = (String) object;
                    // Pass it in raw so it could be potentially converted to
                    // another data type by an xbean-reflect property editor
                    objectRecipe.setProperty(prefix + injection.getName(), string);
                } else {
                    objectRecipe.setProperty(prefix + injection.getName(), new StaticRecipe(object));
                }
            } catch (NamingException e) {
                logger.warning("Injection data not found in enc: jndiName='" + injection.getJndiName() + "', target=" + injection.getTarget() + "/" + injection.getName());
            }
        }
    }

    private boolean hasSetSessionContext(Class beanClass) {
        try {
            beanClass.getMethod("setSessionContext", SessionContext.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private SessionContext createSessionContext() {
        return new SingletonContext(transactionManager, securityService);
    }

    /**
     * All instances are removed from the pool in getInstance(...).  They are only
     * returned by the StatelessContainer via this method under two circumstances.
     *
     * 1.  The business method returns normally
     * 2.  The business method throws an application exception
     *
     * Instances are not returned to the pool if the business method threw a system
     * exception.
     *
     * @param callContext
     * @param bean
     * @throws OpenEJBException
     */
    public void poolInstance(ThreadContext callContext, Object bean) throws OpenEJBException {
        if (bean == null) {
            throw new SystemException("Invalid arguments");
        }

        CoreDeploymentInfo deploymentInfo = callContext.getDeploymentInfo();
        Data data = (Data) deploymentInfo.getContainerData();
        Stack pool = data.getPool();

        if (strictPooling) {
            pool.push(bean);
            semaphores.get(deploymentInfo.getDeploymentID()).release();
        } else {
            if (pool.size() >= poolLimit) {
                freeInstance(callContext, (Instance)bean);
            } else {
                pool.push(bean);
            }
        }
    }

    private void freeInstance(ThreadContext callContext, Instance instance) {
        try {
            callContext.setCurrentOperation(Operation.PRE_DESTROY);
            callContext.setCurrentAllowedStates(SingletonContext.getStates());
            CoreDeploymentInfo deploymentInfo = callContext.getDeploymentInfo();

            Method remove = instance.bean instanceof SessionBean? deploymentInfo.getCreateMethod(): null;

            List<InterceptorData> callbackInterceptors = deploymentInfo.getCallbackInterceptors();
            InterceptorStack interceptorStack = new InterceptorStack(instance.bean, remove, Operation.PRE_DESTROY, callbackInterceptors, instance.interceptors);

            interceptorStack.invoke();
        } catch (Throwable re) {
            logger.error("The bean instance " + instance + " threw a system exception:" + re, re);
        }

    }

    /**
     * This method has no work to do as all instances are removed from
     * the pool on getInstance(...) and not returned via poolInstance(...)
     * if they threw a system exception.
     *
     * @param callContext
     * @param bean
     */
    public void discardInstance(ThreadContext callContext, Object bean) {

    }

    public void deploy(CoreDeploymentInfo deploymentInfo) {
        Data data = new Data(poolLimit);
        deploymentInfo.setContainerData(data);      
        if (this.strictPooling) {
            this.semaphores.put(deploymentInfo.getDeploymentID(), new Semaphore(poolLimit));
        }

    }

    public void undeploy(CoreDeploymentInfo deploymentInfo) {
        Data data = (Data) deploymentInfo.getContainerData();
        if (this.strictPooling) {
            semaphores.remove(deploymentInfo.getDeploymentID());
        }
        if (data == null) return;
        Stack pool = data.getPool();
        //TODO ejbRemove on each bean in pool.
        //clean pool
        deploymentInfo.setContainerData(null);
    }

    private static final class Data {
        private final Stack pool;

        public Data(int poolLimit) {
            pool = new LinkedListStack(poolLimit);
        }

        public Stack getPool() {
            return pool;
        }
    }

}
