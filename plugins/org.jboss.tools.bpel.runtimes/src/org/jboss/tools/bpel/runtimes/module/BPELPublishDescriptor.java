/******************************************************************************* 
 * Copyright (c) 2014 Red Hat, Inc. 
 * Distributed under license by Red Hat, Inc. All rights reserved. 
 * This program is made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.eclipse.org/legal/epl-v10.html 
 * 
 * Contributors: 
 * Red Hat, Inc. - initial API and implementation 
 ******************************************************************************/ 
package org.jboss.tools.bpel.runtimes.module;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.wst.server.core.IServer;
import org.jboss.ide.eclipse.as.core.JBossServerCorePlugin;
import org.jboss.tools.foundation.core.xml.IMemento;
import org.jboss.tools.foundation.core.xml.XMLMemento;


/**
 * This class will keep track of all versions of a module
 * that have been published. A BPEL publisher behaves in a unique fashion,
 * appending a timestamp and deploying a new version on each publish request.
 * 
 * Because of this, the full list of output files must be cached and maintained,
 * so that requests to remove all or specific deployed versions can be 
 * performed without error.  
 */
public class BPELPublishDescriptor {
	private static final String DEPLOYMENTS = "deployments";
	private static final String PROJECT = "project";
	private static final String NAME = "name";
	private static final String VERSION = "version";

	
	public static String[] getDeployedPathsFromDescriptor(IServer server, IProject project) {
		File f = getDeployDetailsFile(server);
		ArrayList<String> list = new ArrayList<String>();
		if( f.exists() ) {
			try {
				XMLMemento memento = XMLMemento.createReadRoot(new FileInputStream(f));
				IMemento[] projects = memento.getChildren(PROJECT);//$NON-NLS-1$
				for( int i = 0; i < projects.length; i++ ) {
					if( project.getName().equals(projects[i].getString(NAME))) {
						IMemento[] deployments = projects[i].getChildren(VERSION);
						for( int j = 0; j < deployments.length; j++ ) {
							String s = ((XMLMemento)deployments[j]).getTextData();
							if( s != null && !s.equals(""))
								list.add(s);
						}
						break;
					}
				}
			} catch( FileNotFoundException fnfe) {}
		}
		return (String[]) list.toArray(new String[list.size()]);
	}
	
	public static void removeVersionFromDescriptor(IServer server, IProject project, String path) {
		File f = getDeployDetailsFile(server);
		XMLMemento memento = null;
		try {
			memento = XMLMemento.createReadRoot(new FileInputStream(f));
			IMemento[] projects = memento.getChildren(PROJECT);//$NON-NLS-1$
			for( int i = 0; i < projects.length; i++ ) {
				if( project.getName().equals(projects[i].getString(NAME)) ) {
					IMemento[] versions = projects[i].getChildren(VERSION);
					for( int j = 0; j < versions.length; j++ ) {
						if( ((XMLMemento)versions[j]).getTextData().equals(path)) {
							((XMLMemento)projects[i]).removeChild((XMLMemento)versions[j]);
						}
					}
				}
			}
			save(server, memento);
		} catch( FileNotFoundException fnfe) {}
	}

	public static void removeProjectFromDescriptor(IServer server, IProject project) {
		File f = getDeployDetailsFile(server);
		XMLMemento memento = null;
		try {
			memento = XMLMemento.createReadRoot(new FileInputStream(f));
			IMemento[] projects = memento.getChildren(PROJECT);//$NON-NLS-1$
			for( int i = 0; i < projects.length; i++ ) {
				if( project.getName().equals(projects[i].getString(NAME)) ) {
					memento.removeChild((XMLMemento)projects[i]);
				}
			}
			save(server, memento);
		} catch( FileNotFoundException fnfe) {}
	}
	
	public static void addDeployedPathToDescriptor(IServer server, IProject project, IPath path) {
		File f = getDeployDetailsFile(server);
		XMLMemento memento = null;
		try {
			memento = XMLMemento.createReadRoot(new FileInputStream(f));
		} catch( FileNotFoundException fnfe) {}
		
		if( memento == null )
			memento = XMLMemento.createWriteRoot(DEPLOYMENTS);

		IMemento[] projects = memento.getChildren(PROJECT);//$NON-NLS-1$
		boolean projectFound = false;
		for( int i = 0; i < projects.length; i++ ) {
			if( project.getName().equals(projects[i].getString(NAME))) {
				projectFound = true;
				XMLMemento child = (XMLMemento)projects[i].createChild(VERSION);
				child.putTextData(path.toOSString());
			}
		}
		if( !projectFound ) {
			XMLMemento proj = (XMLMemento)memento.createChild(PROJECT);
			proj.putString(NAME, project.getName());
			XMLMemento child = (XMLMemento)proj.createChild(VERSION);
			child.putTextData(path.toOSString());
		}
		save(server, memento);
	}

	
	public static void save(IServer server, XMLMemento memento) {
		try {
			memento.save(new FileOutputStream(getDeployDetailsFile(server)));
		} catch( IOException ioe) {
			// TODO LOG
		}
	}
	
	private static File getDeployDetailsFile(IServer server) {
		return JBossServerCorePlugin.getServerStateLocation(server)
					.append("bpel.deployment.versions").toFile();
	}

}
