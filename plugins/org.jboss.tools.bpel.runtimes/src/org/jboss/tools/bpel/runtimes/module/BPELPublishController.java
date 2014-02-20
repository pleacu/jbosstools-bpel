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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Iterator;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.model.IModuleResource;
import org.jboss.ide.eclipse.as.core.JBossServerCorePlugin;
import org.jboss.ide.eclipse.as.core.server.IDeployableServer;
import org.jboss.ide.eclipse.as.core.util.FileUtil;
import org.jboss.ide.eclipse.as.core.util.IEventCodes;
import org.jboss.ide.eclipse.as.core.util.IJBossToolingConstants;
import org.jboss.ide.eclipse.as.core.util.IWTPConstants;
import org.jboss.ide.eclipse.as.core.util.ModuleResourceUtil;
import org.jboss.ide.eclipse.as.core.util.ProgressMonitorUtil;
import org.jboss.ide.eclipse.as.core.util.ServerConverter;
import org.jboss.ide.eclipse.as.wtp.core.server.behavior.AbstractSubsystemController;
import org.jboss.ide.eclipse.as.wtp.core.server.behavior.IFilesystemController;
import org.jboss.ide.eclipse.as.wtp.core.server.behavior.IPublishControllerDelegate;
import org.jboss.ide.eclipse.as.wtp.core.server.publish.LocalZippedModulePublishRunner;
import org.jboss.ide.eclipse.as.wtp.core.server.publish.PublishModuleFullRunner;
import org.jboss.tools.as.core.server.controllable.util.PublishControllerUtility;

/**
 * This class allows you to publish a BPEL module specifically
 * to a JBossTools server entity. 
 * @author rob.stryker@jboss.com
 *
 */
public class BPELPublishController extends AbstractSubsystemController implements IPublishControllerDelegate {
	private IFilesystemController filesystemController;
	
	public BPELPublishController() {
	}

	/**
	 * The entry point for this publisher. 
	 * The BPEL publisher behaves in the following manner:
	 *    - ignore all incremental publishes
	 *    - create a new jar with a timestamp on a full publish request
	 *    - remove ALL versions of an archive if a 'remove' request is made
	 */
	@Override
	public int publishModule(int kind, int deltaKind, IModule[] module,
			IProgressMonitor monitor) throws CoreException {
		int publishType = PublishControllerUtility.getPublishType(getServer(), module, kind, deltaKind);
		int publishState = IServer.PUBLISH_STATE_UNKNOWN;
		IModule last = module[module.length-1];
		IStatus status = null;
		if(publishType == PublishControllerUtility.REMOVE_PUBLISH){
			// https://jira.jboss.org/browse/JBIDE-7620
			if (last.getProject()!=null)
				removeAll(last.getProject(), monitor);
        } else if( publishType == PublishControllerUtility.FULL_PUBLISH ){
        	// Publish a new version forced, full publish
        	status = fullPublish(module, monitor);
        	publishState = IServer.PUBLISH_STATE_NONE;
        } else if( publishType == PublishControllerUtility.INCREMENTAL_PUBLISH ) {
        	// Do nothing. This is intentional
        	publishState = IServer.PUBLISH_STATE_INCREMENTAL;
        }
        // https://issues.jboss.org/browse/JBDS-1573
        // hack: display a warning dialog.
        // Deployment validation should really be handled as a WizardFragment invoked from
        // org.eclipse.wst.server.ui.internal.wizard.ModifyModulesWizard
        // but there is no WizardFragment extension point for this class...
        // 
		if (status!=null && !status.isOK()) {
			final IStatus s = status;
			Display.getDefault().syncExec(new Runnable() {
				public void run() {
					MessageDialog.openWarning(Display.getDefault()
							.getActiveShell(), Messages.DeployError, s
							.getMessage());
				}
			});
		}
		return publishState;	
	}
	
	
	// Only a full publish can be executed for bpel. 
	// Incremental publishes are intentionally ignored
	private IStatus fullPublish(IModule[] moduleTree, IProgressMonitor monitor) throws CoreException {
		ArrayList<IStatus> resultList = new ArrayList<IStatus>();
		IModule last = moduleTree[moduleTree.length -1];
		IModuleResource[] members = ModuleResourceUtil.getResources(last, new NullProgressMonitor());
		// https://issues.jboss.org/browse/JBDS-1573
		// make sure the project has a deploy.xml (bpel-deploy.xml for backward compatibility).
		IStatus hasDeployXmlStatus = verifyDeployXmlExists(last, members);
		if( !hasDeployXmlStatus.isOK()) 
			return hasDeployXmlStatus;
		
		IPath targetSystemDeployPath = getTargetSystemDeployPath(moduleTree);
		if( shouldZip() ) {
			String moduleName = last.getName();
			IPath temporaryArchive = getMetadataTemporaryLocation().append(moduleName);
			LocalZippedModulePublishRunner runner = new LocalZippedModulePublishRunner(
					getServer(), last,temporaryArchive, null);
			IStatus ret = runner.fullPublishModule(new NullProgressMonitor());
			resultList.add(ret);
			
			// The zipped archive is now stored in temporaryArchive
			if( ret.isOK() ) {
				ret = getFilesystemController().copyFile(temporaryArchive.toFile(), targetSystemDeployPath, monitor);
				resultList.add(ret);
			}
		} else {
			PublishModuleFullRunner runner = new PublishModuleFullRunner(getFilesystemController(), targetSystemDeployPath);
			IStatus[] results = runner.fullPublish(members, monitor);
			resultList.addAll(Arrays.asList(results));
		}
		
		// Add the deployed path to our descriptor which keeps track of all deployed jars
		BPELPublishDescriptor.addDeployedPathToDescriptor(getServer(), last.getProject(), targetSystemDeployPath); // persist it
		
		// Return a coherent status object to summarize the result
		pruneList(resultList);
		if( resultList.size() > 0 ) {
			MultiStatus ms = new MultiStatus(JBossServerCorePlugin.PLUGIN_ID, IEventCodes.JST_PUB_FULL_FAIL, 
					NLS.bind(org.jboss.ide.eclipse.as.core.Messages.FullPublishFail, last.getName()), null);
			for( int i = 0; i < resultList.size(); i++ )
				ms.add(resultList.get(i));
			return ms;
		}
		return Status.OK_STATUS;
	}
	

	/*
	 * get the filesystem controller for transfering files for this server
	 */
	protected IFilesystemController getFilesystemController() throws CoreException {
		if( filesystemController == null ) {
			filesystemController = (IFilesystemController)findDependencyFromBehavior(IFilesystemController.SYSTEM_ID);
		}
		return filesystemController;
	}
	
	
	/**
	 * Verify the deploy.xml file exists.
	 * @param members
	 * @return An OK_STATUS if it exists, an error status if it does not
	 */
	private IStatus verifyDeployXmlExists(IModule module, IModuleResource[] members) {
		boolean hasDeployXML = false;
		for (int i=0; i<members.length; ++i) {
			IModuleResource res = members[i];
			String name = res.getName();
			if ("deploy.xml".equals(name) || "bpel-deploy.xml".equals(name)) {
				hasDeployXML = true;
				break;
			}
		}
		if (!hasDeployXML) {
			Status ms = new Status(IStatus.ERROR,JBossServerCorePlugin.PLUGIN_ID, IEventCodes.JST_PUB_FULL_FAIL, 
					NLS.bind(Messages.MissingDeployXML, module.getName()), null);
			return ms;
		}
		return Status.OK_STATUS;
	}
	
	// Prune out ok status, return only error or warning status objects
	private void pruneList(ArrayList<IStatus> list) {
		Iterator<IStatus> i = list.iterator();
		while(i.hasNext()) {
			if( i.next().isOK())
				i.remove();
		}
	}
	
	private boolean shouldZip() {
		IDeployableServer ds = ServerConverter.getDeployableServer(getServer());
		return ds == null || ds.zipsWTPDeployments();
	}
	
	/**
	 * Will return the deployment path on the remote or target system, 
	 * with the last segment replaced by a jar name with a timestamp. 
	 * 
	 * @param moduleTree
	 * @return
	 */
	private IPath getTargetSystemDeployPath(IModule[] moduleTree) {
		IDeployableServer ds = ServerConverter.getDeployableServer(getServer());
		IPath path = ds.getDeploymentLocation(moduleTree, true);
		path = path.removeLastSegments(1).append(getNewLastSegment(moduleTree));
		return path;
	}
	
	/**
	 * Will create a new jar name with a timestamp on it
	 * 
	 * @param moduleTree
	 * @return
	 */
	private static String getNewLastSegment(IModule[] moduleTree) {
		IModule last = moduleTree[moduleTree.length-1];
		Calendar cal = Calendar.getInstance();
		StringBuffer lastSeg = new StringBuffer();
		lastSeg.append(last.getName());
		lastSeg.append("-");
		lastSeg.append(formatString(cal.get(Calendar.YEAR)));
		lastSeg.append(formatString(cal.get(Calendar.MONTH) + 1));
		lastSeg.append(formatString(cal.get(Calendar.DAY_OF_MONTH)));
		lastSeg.append(formatString(cal.get(Calendar.HOUR_OF_DAY)));
		lastSeg.append(formatString(cal.get(Calendar.MINUTE)));
		lastSeg.append(formatString(cal.get(Calendar.SECOND)));
		lastSeg.append(IWTPConstants.EXT_JAR);
		return lastSeg.toString();
	}
	
	/**
	 * Ensure that any integer is at least 2 digits.
	 * For example, change month "8" into "08"
	 * 
	 * @param dateUnit
	 * @return
	 */
	private static String formatString(int dateUnit){
		if(String.valueOf(dateUnit).length() < 2){
			return "0" + dateUnit;
		}
		
		return String.valueOf(dateUnit);
	}
	
	
	private void removeAll(IProject project, IProgressMonitor monitor)  throws CoreException {
		String[] paths = BPELPublishDescriptor.getDeployedPathsFromDescriptor(getServer(), project);
		monitor.beginTask("Removing all bpel modules", paths.length * 100);
		IFilesystemController controller = getFilesystemController();
		for( int i = 0; i < paths.length; i++ ) {
			// remove them all, with full force!!! >=[
			controller.deleteResource(new Path(paths[i]), ProgressMonitorUtil.getSubMon(monitor, 100));
		}
		BPELPublishDescriptor.removeProjectFromDescriptor(getServer(), project);
	}
	
	
	// Get a temporary location to compile this module
	private IPath getMetadataTemporaryLocation() {
		IPath deployRoot = JBossServerCorePlugin.getServerStateLocation(getServer()).
			append(IJBossToolingConstants.TEMP_REMOTE_DEPLOY).makeAbsolute();
		deployRoot.toFile().mkdirs();
		return deployRoot;
	}
}
