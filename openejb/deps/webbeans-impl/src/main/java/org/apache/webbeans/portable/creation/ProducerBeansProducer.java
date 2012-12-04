/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.webbeans.portable.creation;

import org.apache.webbeans.component.AbstractProducerBean;

/**
 * Implementation for producer beans.
 * 
 * @version $Rev: 952250 $ $Date: 2010-06-07 16:39:41 +0200 (lun., 07 juin 2010) $
 *
 * @param <T> producer return type info
 */
public class ProducerBeansProducer<T> extends AbstractProducer<T>
{
    /**
     * Creats a new producer bean producer.
     * 
     * @param bean producer bean
     */
    public ProducerBeansProducer(AbstractProducerBean<T> bean)
    {
        super(bean);
    }    
}