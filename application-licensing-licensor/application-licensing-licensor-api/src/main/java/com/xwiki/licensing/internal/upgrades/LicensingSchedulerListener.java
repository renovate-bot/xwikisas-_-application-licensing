/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xwiki.licensing.internal.upgrades;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.quartz.SchedulerException;
import org.quartz.Trigger.TriggerState;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.extension.event.ExtensionInstalledEvent;
import org.xwiki.extension.repository.internal.installed.DefaultInstalledExtension;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.plugin.scheduler.JobState;
import com.xpn.xwiki.plugin.scheduler.SchedulerPlugin;

/**
 * Ensure that {@link LicensedExtensionUpgradeJob} and {@link NewExtensionVersionAvailableJob} are scheduled after
 * licensing install or upgrade. Reschedule {@link LicensedExtensionUpgradeJob} and
 * {@link NewExtensionVersionAvailableJob} to work around XWIKI-14494: Java scheduler job coming from an extension is
 * not rescheduled when the extension is upgraded. The unschedule / schedule process should be removed once the issue is
 * fixed and licensing depends on a version of XWiki >= the version where is fixed.
 *
 * @version $Id$
 * @since 1.17
 */
@Component
@Named(LicensingSchedulerListener.ROLE_HINT)
@Singleton
public class LicensingSchedulerListener extends AbstractEventListener implements Initializable
{
    /**
     * The role hint of this component.
     */
    public static final String ROLE_HINT = "LicensingSchedulerListener";

    /**
     * The id of application-licensing-licensor-api module..
     */
    protected static final String LICENSOR_API_ID = "com.xwiki.licensing:application-licensing-licensor-api";

    protected static final List<String> CODE_SPACE = Arrays.asList("Licenses", "Code");

    protected static final LocalDocumentReference EXTENSION_UPGRADE_JOB_DOC =
        new LocalDocumentReference(CODE_SPACE, "LicensedExtensionUpgradeJob");

    protected static final LocalDocumentReference NEW_VERSION_JOB_DOC =
        new LocalDocumentReference(CODE_SPACE, "NewExtensionVersionAvailableJob");

    private static final List<Event> EVENTS = Arrays.asList(new ExtensionInstalledEvent());

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    /**
     * Constructor.
     */
    public LicensingSchedulerListener()
    {
        super(ROLE_HINT, EVENTS);
    }

    /**
     * The unschedule / schedule process should be done at ExtensionUpgradedEvent, but for avoiding XCOMMONS-751:
     * Getting wrong component instance during JAR extension upgrade, it is done at initialization step, since when the
     * extension is initialized after an upgrade, all the extension's listeners are initialized. After the issue is
     * fixed and licensing starts depending on a version of XWiki >= the version where is fixed, then this code should
     * be moved inside a ExtensionUpgradedEvent listener.
     *
     * @see org.xwiki.component.phase.Initializable#initialize()
     */
    @Override
    public void initialize() throws InitializationException
    {
        try {
            // Don't trigger the rescheduling process at xwiki startup time.
            if (this.contextProvider.get() != null) {
                scheduleJob(true, EXTENSION_UPGRADE_JOB_DOC);
                scheduleJob(true, NEW_VERSION_JOB_DOC);
            }
        } catch (XWikiException | SchedulerException e) {
            logger.warn("An error occurred while rescheduling LicensedExtensionUpgradeJob, meaning automatic upgrades "
                + "on pro apps might be affected. Please reschedule this job manually or contact support. ", e);
        }
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        String extensionId = ((DefaultInstalledExtension) source).getId().getId();

        if (event instanceof ExtensionInstalledEvent && extensionId.equals(LICENSOR_API_ID)) {
            try {
                scheduleJob(false, EXTENSION_UPGRADE_JOB_DOC);
                scheduleJob(false, NEW_VERSION_JOB_DOC);
            } catch (XWikiException | SchedulerException e) {
                throw new RuntimeException("Error while scheduling LicensedExtensionUpgradeJob after licensing install",
                    e);
            }
        }
    }

    protected void scheduleJob(boolean doReschedule, LocalDocumentReference jobDocReference)
        throws XWikiException, SchedulerException
    {
        XWikiContext xcontext = contextProvider.get();

        SchedulerPlugin scheduler = (SchedulerPlugin) xcontext.getWiki().getPluginManager().getPlugin("scheduler");
        XWikiDocument jobDoc = xcontext.getWiki().getDocument(jobDocReference, xcontext);
        BaseObject job = jobDoc.getXObject(SchedulerPlugin.XWIKI_JOB_CLASSREFERENCE);
        JobState jobState = scheduler.getJobStatus(job, xcontext);

        if (doReschedule && jobState.getQuartzState().equals(TriggerState.NORMAL)) {
            scheduler.unscheduleJob(job, xcontext);
            scheduler.scheduleJob(job, xcontext);
        } else if (jobState.getQuartzState().equals(TriggerState.NONE)) {
            scheduler.scheduleJob(job, xcontext);
        }
    }
}
