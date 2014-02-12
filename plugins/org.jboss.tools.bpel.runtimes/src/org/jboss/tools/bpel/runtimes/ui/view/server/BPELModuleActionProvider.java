package org.jboss.tools.bpel.runtimes.ui.view.server;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.ui.navigator.CommonActionProvider;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.navigator.ICommonActionExtensionSite;
import org.eclipse.ui.navigator.ICommonViewerSite;
import org.eclipse.ui.navigator.ICommonViewerWorkbenchSite;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.ui.internal.view.servers.ModuleServer;
import org.jboss.ide.eclipse.as.core.util.JBossServerBehaviorUtils;
import org.jboss.ide.eclipse.as.wtp.core.server.behavior.IControllableServerBehavior;
import org.jboss.ide.eclipse.as.wtp.core.server.behavior.IFilesystemController;
import org.jboss.tools.bpel.runtimes.RuntimesPlugin;
import org.jboss.tools.bpel.runtimes.module.BPELPublishDescriptor;
import org.jboss.tools.bpel.runtimes.ui.view.server.BPELModuleContentProvider.BPELVersionDeployment;

public class BPELModuleActionProvider extends CommonActionProvider {

	private ICommonActionExtensionSite actionSite;
	private Action undeployVersionAction;
	private IStructuredSelection lastSelection;
	public BPELModuleActionProvider() {
		super();
	}

    public void dispose() {
    	super.dispose();
    }

	public void init(ICommonActionExtensionSite aSite) {
		super.init(aSite);
		this.actionSite = aSite;
		createActions(aSite);
	}

	protected void createActions(ICommonActionExtensionSite aSite) {
		ICommonViewerSite site = aSite.getViewSite();
		if( site instanceof ICommonViewerWorkbenchSite ) {
			StructuredViewer v = aSite.getStructuredViewer();
			if( v instanceof CommonViewer ) {
				CommonViewer cv = (CommonViewer)v;
				ICommonViewerWorkbenchSite wsSite = (ICommonViewerWorkbenchSite)site;
				undeployVersionAction = new Action() {
					public void run() {
						runUndeployVersion();
						refreshViewer(getLastServer());
					}
				};
				undeployVersionAction.setText("Undeploy Version");
				undeployVersionAction.setDescription("Undeploy this version of the module");
				//undeployVersionAction.setImageDescriptor(JBossServerUISharedImages.getImageDescriptor(JBossServerUISharedImages.PUBLISH_IMAGE));
			}
		}
	}

	protected void runUndeployVersion() {
		Object firstSel = lastSelection.getFirstElement();
		if( firstSel instanceof BPELVersionDeployment ) {
			BPELVersionDeployment deployment = (BPELVersionDeployment)firstSel;
			removeVersion(deployment.getModuleServer().server, 
					deployment.getProject(), deployment.getPath());
		}
	}
	

	public static void removeVersion(IServer server, IProject project, String path) {
		// delete file
		IControllableServerBehavior beh = JBossServerBehaviorUtils.getControllableBehavior(server);
		CoreException ce = null;
		try {
			if( beh != null ) { 
				IFilesystemController filesystemController = (IFilesystemController)beh.getController(IFilesystemController.SYSTEM_ID);
				if( filesystemController != null ) {
					filesystemController.deleteResource(new Path(path), new NullProgressMonitor());
				}
				BPELPublishDescriptor.removeVersionFromDescriptor(server, project, path);
				return;
			}
		} catch(CoreException ce2) {
			ce = ce2;
		}
		IStatus s = new Status(IStatus.ERROR, RuntimesPlugin.PLUGIN_ID, "Unable to remove bpel module version", ce);
		RuntimesPlugin.log(new CoreException(s), IStatus.ERROR);
	}
	
	protected IServer getLastServer() {
		Object firstSel = lastSelection.getFirstElement();
		if( firstSel instanceof IServer ) 
			return (IServer)firstSel;
		if( firstSel instanceof ModuleServer )
			return ((ModuleServer)firstSel).getServer();
		if( firstSel instanceof BPELVersionDeployment ) 
			return ((BPELVersionDeployment)firstSel).getModuleServer().getServer();
		return null;
	}
	
	protected void refreshViewer(Object o) {
		actionSite.getStructuredViewer().refresh(o);
	}

	
	public void fillContextMenu(IMenuManager menu) {
		lastSelection = getSelection();
		if( lastSelection.size() == 1 ) {
			Object sel = lastSelection.getFirstElement();
			if( sel instanceof BPELVersionDeployment )
				menu.add(undeployVersionAction);
		}
	}
	
	public IStructuredSelection getSelection() {
		ICommonViewerSite site = actionSite.getViewSite();
		IStructuredSelection selection = null;
		if (site instanceof ICommonViewerWorkbenchSite) {
			ICommonViewerWorkbenchSite wsSite = (ICommonViewerWorkbenchSite) site;
			selection = (IStructuredSelection) wsSite.getSelectionProvider()
					.getSelection();
			return selection;
		}
		return new StructuredSelection();
	}
	
}
