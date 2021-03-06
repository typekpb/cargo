/*
 * ========================================================================
 *
 * Codehaus CARGO, copyright 2004-2011 Vincent Massol, 2012-2016 Ali Tokmen.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ========================================================================
 */
package org.codehaus.cargo.maven2;

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.cargo.container.deployer.DeployableMonitor;
import org.codehaus.cargo.container.deployer.DeployableMonitorListener;
import org.codehaus.cargo.generic.deployer.DefaultDeployerFactory;
import org.codehaus.cargo.generic.deployer.DeployerFactory;
import org.codehaus.cargo.maven2.configuration.Deployable;
import org.codehaus.cargo.maven2.deployer.DefaultDeployableMonitorFactory;
import org.codehaus.cargo.maven2.deployer.DeployableMonitorFactory;

/**
 * Common mojo for all deployer actions (start deployable, stop deployable, deploy deployable,
 * undeploy deployable, etc).
 */
public abstract class AbstractDeployerMojo extends AbstractCargoMojo
{
    /**
     * {@link DeployableMonitorListener} that logs.
     */
    public class DeployerListener implements DeployableMonitorListener
    {
        /**
         * {@link Deployable} to listen.
         */
        private org.codehaus.cargo.container.deployable.Deployable deployable;

        /**
         * Saves all attributes.
         * @param deployable {@link Deployable} to listen.
         */
        public DeployerListener(org.codehaus.cargo.container.deployable.Deployable deployable)
        {
            this.deployable = deployable;
        }

        /**
         * {@inheritDoc}.
         */
        @Override
        public void deployed()
        {
            getLog().debug("Watchdog finds [" + this.deployable.getFile() + "] deployed.");
        }

        /**
         * {@inheritDoc}.
         */
        @Override
        public void undeployed()
        {
            getLog().debug("Watchdog finds [" + this.deployable.getFile() + "] not deployed yet.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doExecute() throws MojoExecutionException
    {
        if (getCargoProject().getPackaging() == null || !getCargoProject().isJ2EEPackaging())
        {
            if (getDeployablesElement() == null || getDeployablesElement().length == 0)
            {
                getLog().info("There's nothing to deploy or undeploy");
                return;
            }
        }

        org.codehaus.cargo.container.Container container = createContainer();
        org.codehaus.cargo.container.deployer.Deployer deployer = createDeployer(container);

        performDeployerActionOnAllDeployables(container, deployer);
    }

    /**
     * Create a deployer.
     * @param container Container.
     * @return Deployer for <code>container</code>.
     * @throws MojoExecutionException If deployer creation fails.
     */
    protected org.codehaus.cargo.container.deployer.Deployer createDeployer(
        org.codehaus.cargo.container.Container container) throws MojoExecutionException
    {
        org.codehaus.cargo.container.deployer.Deployer deployer;

        // Use a deployer matching the container's type if none is specified.
        // @see DeployerFactory#createDeployer(Container)
        if (getDeployerElement() == null)
        {
            deployer = createDeployerFactory().createDeployer(container);
        }
        else
        {
            deployer = getDeployerElement().createDeployer(container);
        }

        return deployer;
    }

    /**
     * @return Deployer factory.
     */
    protected DeployerFactory createDeployerFactory()
    {
        return new DefaultDeployerFactory();
    }

    /**
     * Perform deployment action on all deployables (defined in the deployer configuration element
     * and on the autodeployable).
     * 
     * @param container the container to deploy into
     * @param deployer the deployer to use to deploy into the container
     * @throws MojoExecutionException in case of a deployment error
     */
    private void performDeployerActionOnAllDeployables(
        org.codehaus.cargo.container.Container container,
        org.codehaus.cargo.container.deployer.Deployer deployer) throws MojoExecutionException
    {
        getLog().debug("Performing deployment action into [" + container.getName() + "]...");

        List<Deployable> deployableElements = new ArrayList<Deployable>();

        if (getDeployablesElement() != null)
        {
            for (Deployable deployableElement : getDeployablesElement())
            {
                if (!deployableElements.contains(deployableElement))
                {
                    deployableElements.add(deployableElement);
                }
            }
        }

        for (Deployable deployableElement : deployableElements)
        {
            org.codehaus.cargo.container.deployable.Deployable deployable =
                deployableElement.createDeployable(container.getId(), getCargoProject());
            DeployableMonitor monitor = createDeployableMonitor(container, deployableElement,
                    deployable);

            performDeployerActionOnSingleDeployable(deployer, deployable, monitor);
        }

        // Perform deployment action on the autodeployable (if any).
        if (getCargoProject().getPackaging() != null && getCargoProject().isJ2EEPackaging())
        {
            Deployable[] deployableElementsArray = new Deployable[deployableElements.size()];
            deployableElements.toArray(deployableElementsArray);

            if (!containsAutoDeployable(deployableElementsArray))
            {
                // Deployable monitor is always null here because if the user has explicitly
                // specified deployable then the auto deployable has already been deployed...
                performDeployerActionOnSingleDeployable(deployer,
                    createAutoDeployDeployable(container), null);
            }
        }
    }

    /**
     * Perform a deployer action on a single deployable.
     * @param deployer Deployer.
     * @param deployable Deployable.
     * @param monitor Deployable monitor.
     */
    protected abstract void performDeployerActionOnSingleDeployable(
        org.codehaus.cargo.container.deployer.Deployer deployer,
        org.codehaus.cargo.container.deployable.Deployable deployable,
        org.codehaus.cargo.container.deployer.DeployableMonitor monitor);

    /**
     * Create a deployable monitor.
     * @param container Container where is deployable deployed.
     * @param deployableElement {@link Deployable} containing monitoring info.
     * @param deployable {@link org.codehaus.cargo.container.deployable.Deployable} to monitor.
     * @return Deployable monitor with specified arguments.
     */
    private DeployableMonitor createDeployableMonitor(
            org.codehaus.cargo.container.Container container,
            Deployable deployableElement,
            org.codehaus.cargo.container.deployable.Deployable deployable)
    {
        DeployableMonitorFactory monitorFactory = new DefaultDeployableMonitorFactory();
        DeployableMonitor monitor = monitorFactory.
                createDeployableMonitor(container, deployableElement);

        if (monitor != null)
        {
            DeployerListener listener = new DeployerListener(deployable);
            monitor.registerListener(listener);
        }

        return monitor;
    }
}
