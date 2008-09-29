/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openejb.helper.annotation.wizards;

import java.util.ArrayList;
import java.util.List;

import org.apache.openejb.devtools.core.JDTFacade;
import org.apache.openejb.plugins.common.*;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.xml.sax.InputSource;

public class EJBMigrationRefactoring extends Refactoring {

	protected String ejbJarXmlFile;
	protected String openEjbJarXmlFile;
	protected IProject project;
	protected RefactoringStatus status;
	private final IWorkspaceRoot workspaceRoot;
	protected boolean ejb3Interfaces;
	protected boolean remoteAndRemoteHomeAnnotations;
	protected boolean convertEntityBeansToPojos;
	
	public EJBMigrationRefactoring(IWorkspaceRoot workspaceRoot) {
		this.workspaceRoot = workspaceRoot;
		this.status = new RefactoringStatus();
	}

	@Override
	public RefactoringStatus checkFinalConditions(IProgressMonitor pm) throws CoreException, OperationCanceledException {
		status = new RefactoringStatus();
		
		if (ejbJarXmlFile == null || ejbJarXmlFile.length() == 0) {
			status.addError(Messages.getString("org.apache.openejb.helper.annotation.wizards.ejbMigrationWzd.errorMsg.1")); //$NON-NLS-1$
		}

		IFile file = project.getFile(ejbJarXmlFile);
		if (! (file.exists())) {
			status.addError(Messages.getString("org.apache.openejb.helper.annotation.wizards.ejbMigrationWzd.errorMsg.2")); //$NON-NLS-1$
		}

		
		return status;
	}

	@Override
	public RefactoringStatus checkInitialConditions(IProgressMonitor pm) throws CoreException, OperationCanceledException {
		return new RefactoringStatus();
	}

	@Override
	public Change createChange(IProgressMonitor pm) throws CoreException, OperationCanceledException {
		try {
			InputSource ejbJarInputSource = null;
			InputSource openEjbJarInputSource = null;
			
			if (ejbJarXmlFile != null && ejbJarXmlFile.length() > 0) {
				IFile ejbJarFile = project.getFile(ejbJarXmlFile);
				if (!(ejbJarFile.exists())) {
					return null;
				}

				ejbJarInputSource = new InputSource(ejbJarFile.getContents());
			}
			
			if (openEjbJarXmlFile != null && openEjbJarXmlFile.length() > 0) {
				IFile openEjbJarFile = project.getFile(openEjbJarXmlFile);
				if (openEjbJarFile.exists()) {
					openEjbJarInputSource = new InputSource(openEjbJarFile.getContents());
				}
			}
			
			JDTFacade jdtFacade = new JDTFacade(project);
			
			List<Converter> converterList = new ArrayList<Converter>();
			converterList.add(new SessionBeanConverter(jdtFacade));
			converterList.add(new EntityBeanConverter(jdtFacade));
			converterList.add(new EjbReferencesConverter(jdtFacade));
			
			if (ejb3Interfaces) {
				converterList.add(new SessionBeanInterfaceModifier(jdtFacade));
			}
			
			if (remoteAndRemoteHomeAnnotations) {
				converterList.add(new SessionBeanRemoteAnnotationAdder(jdtFacade));
			}
			
			if (convertEntityBeansToPojos) {
				converterList.add(new EntityBeanPojoConverter(jdtFacade));
			}
			
			Converter[] converters = converterList.toArray(new Converter[0]);
			
			OpenEjbXmlConverter converter = new OpenEjbXmlConverter(converters, new EclipseProjectClassLoader(getClass().getClassLoader(), project));
			converter.convert(ejbJarInputSource, openEjbJarInputSource);

			String[] warnings = jdtFacade.getWarnings();
			for (String warning : warnings) {
				status.addWarning(warning);
			}

			return jdtFacade.getChange();

		} catch (Exception e) {
			status.addFatalError(Messages.getString("org.apache.openejb.helper.annotation.wizards.ejbMigrationWzd.fatalError") + ":" + e.getLocalizedMessage());
			return null;
		}
	}

	@Override
	public String getName() {
		return Messages.getString("org.apache.openejb.helper.annotation.wizards.ejbMigrationWzd.refactoringName"); //$NON-NLS-1$
	}

	public String getEjbJarXmlFile() {
		if (ejbJarXmlFile == null)
			ejbJarXmlFile = ""; //$NON-NLS-1$
		
		return ejbJarXmlFile;
	}

	public void setEjbJarXmlFile(String ejbJarXmlFile) {
		this.ejbJarXmlFile = ejbJarXmlFile;
	}

	public String getOpenEjbJarXmlFile() {
		if (openEjbJarXmlFile == null)
			openEjbJarXmlFile = ""; //$NON-NLS-1$
		
		return openEjbJarXmlFile;
	}

	public void setOpenEjbJarXmlFile(String openEjbJarXmlFile) {
		this.openEjbJarXmlFile = openEjbJarXmlFile;
	}

	public IProject getProject() {
		return project;
	}

	public void setProject(IProject project) {
		this.project = project;
	}

	public IWorkspaceRoot getWorkspaceRoot() {
		return workspaceRoot;
	}

	public boolean isEjb3Interfaces() {
		return ejb3Interfaces;
	}

	public void setEjb3Interfaces(boolean ejb3Interfaces) {
		this.ejb3Interfaces = ejb3Interfaces;
	}

	public boolean isRemoteAndRemoteHomeAnnotations() {
		return remoteAndRemoteHomeAnnotations;
	}

	public void setRemoteAndRemoteHomeAnnotations(boolean remoteAndRemoteHomeAnnotations) {
		this.remoteAndRemoteHomeAnnotations = remoteAndRemoteHomeAnnotations;
	}

	public boolean isConvertEntityBeansToPojos() {
		return convertEntityBeansToPojos;
	}

	public void setConvertEntityBeansToPojos(boolean convertEntityBeansToPojos) {
		this.convertEntityBeansToPojos = convertEntityBeansToPojos;
	}
}
