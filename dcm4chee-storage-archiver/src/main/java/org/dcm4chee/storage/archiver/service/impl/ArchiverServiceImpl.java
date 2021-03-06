/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2012-2014
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4chee.storage.archiver.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.dcm4che3.net.Device;
import org.dcm4chee.storage.ContainerEntry;
import org.dcm4chee.storage.RetrieveContext;
import org.dcm4chee.storage.StorageContext;
import org.dcm4chee.storage.archiver.service.ArchiverContext;
import org.dcm4chee.storage.archiver.service.ArchiverService;
import org.dcm4chee.storage.archiver.service.ContainerEntriesStored;
import org.dcm4chee.storage.conf.Archiver;
import org.dcm4chee.storage.conf.StorageDevice;
import org.dcm4chee.storage.conf.StorageDeviceExtension;
import org.dcm4chee.storage.conf.StorageSystem;
import org.dcm4chee.storage.service.RetrieveService;
import org.dcm4chee.storage.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Steve Kroetsch<stevekroetsch@hotmail.com>
 *
 */
@ApplicationScoped
public class ArchiverServiceImpl implements ArchiverService {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiverServiceImpl.class);

    @Resource(mappedName = "java:/JmsXA")
    private ConnectionFactory connFactory;

    @Inject
    private StorageService storageService;

    @Inject
    private RetrieveService retrieveService;

    @Inject @StorageDevice
    private Device device;

    @Inject
    @ContainerEntriesStored
    private Event<ArchiverContext> containerStored;

    @Override
    public ArchiverContext createContext(ArchiverService archiverService, String groupID,
            String name) {
        ArchiverContext context = new ArchiverContext(archiverService, name, groupID);
        return context;
    }

    @Override
    public void scheduleStore(ArchiverContext context, long delay) {
        scheduleStore(context, 0, delay);
    }

    private void scheduleStore(ArchiverContext context, int retries, long delay) {
        try {
            Connection conn = connFactory.createConnection();
            try {
                Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Queue queue = context.getArchiverService().lookupQueue(context);
                MessageProducer producer = session.createProducer(queue);
                ObjectMessage msg = session.createObjectMessage(context);
                msg.setIntProperty("Retries", retries);
                if (delay > 0) {
                    msg.setLongProperty("_HQ_SCHED_DELIVERY", System.currentTimeMillis() + delay);
                }
                msg.setJMSCorrelationID(context.getJMSCorrelationID());
                producer.send(msg);
            } finally {
                conn.close();
            }
        } catch (JMSException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Queue lookupQueue(ArchiverContext context) {
        Archiver archiver = storageDeviceExtension().getArchiver();
        Map<String, String> queueNameMap = archiver.getQueueNameMap();
        String name = (queueNameMap == null) ? null : queueNameMap.get(context
                .getStorageSystemGroupID());
        if (name == null) {
            name = archiver.getDefaultQueueName();
        }
        try {
            InitialContext ctx = new InitialContext();
            return (Queue) ctx.lookup(name);
        } catch (NamingException e) {
            throw new RuntimeException("Failed to lookup " + name, e);
        }
    }

    @Override
    public void store(ArchiverContext context, int retries) {
        try {
            resolveContainerEntries(context);
            StorageSystem storageSystem = selectStorageSystem(context);
            boolean useContainer = storageSystem.getStorageSystemGroup().getContainer() != null;
            if (useContainer) {
                makeContainer(storageSystem, context);
            } else {
                storeFiles(storageSystem, context);
            }
            context.setStorageSystemID(storageSystem.getStorageSystemID());
            context.setObjectStatus(storageDeviceExtension().getArchiver().getObjectStatus());
            containerStored.fire(context);
        } catch (Exception e) {
            Archiver archiver = storageDeviceExtension().getArchiver();
            if (archiver != null && retries < archiver.getMaxRetries()) {
                int delay = archiver.getRetryInterval();
                LOG.warn(
                        "Failed to store container entries to Storage System Group {} - retry ({}/{}) in {}s:",
                        context.getStorageSystemGroupID(), ++retries, archiver.getMaxRetries(),
                        delay, e);
                scheduleStore(context, retries, delay * 1000L);
            } else {
                LOG.error("Failed to store container entries to Storage System Group {}",
                        context.getStorageSystemGroupID(), e);
            }
        }
    }

    private StorageDeviceExtension storageDeviceExtension() {
        return device.getDeviceExtension(StorageDeviceExtension.class);
    }

    private void resolveContainerEntries(ArchiverContext context) throws IOException,
            InterruptedException {
        retrieveService.resolveContainerEntries(context.getEntries());
    }

    private StorageSystem selectStorageSystem(ArchiverContext context) throws IOException {
        long reserveSpace = 0L;
        for (ContainerEntry entry : context.getEntries())
            reserveSpace += Files.size(entry.getSourcePath());

        String groupID = context.getStorageSystemGroupID();
        StorageSystem storageSystem = storageService.selectStorageSystem(groupID, reserveSpace);
        if (storageSystem == null)
            throw new IOException("No writeable Storage System in Storage System Group " + groupID);
        return storageSystem;
    }

    private void makeContainer(StorageSystem storageSystem, ArchiverContext context)
            throws Exception {
        List<ContainerEntry> entries = context.getEntries();
        StorageContext storageCtx = storageService.createStorageContext(storageSystem);
        String name = context.getName();
        try {
            storageService.storeContainerEntries(storageCtx, entries, name);
            RetrieveContext retrieveCtx = retrieveService.createRetrieveContext(storageSystem);
            if (storageDeviceExtension().getArchiver().isVerifyContainer())
                retrieveService.verifyContainer(retrieveCtx, name, entries);
            LOG.info("Stored container entries: {} to {}@{}", entries.size(), entries, name,
                    storageSystem);
        } catch (Exception e) {
            try {
                storageService.deleteObject(storageCtx, name);
            } catch (Exception e1) {
                LOG.warn("Failed to delete container {}@{}", name, storageSystem, e1);
            }
            throw e;
        }
    }

    private void storeFiles(StorageSystem storageSystem, ArchiverContext context) throws Exception {
        context.setNotInContainer(true);
        List<ContainerEntry> entries = context.getEntries();
        StorageContext storageCtx = storageService.createStorageContext(storageSystem);
        String name = context.getName();
        List<String> entryNames = new ArrayList<String>();
        String entrySeparator = storageDeviceExtension().getArchiver().getEntrySeparator();
        String entryName;
        for (ContainerEntry entry : entries) {
            // TODO: extended error handling (keep successfully copied files and
            // retry the failed ones)
            try {
                entryName = name + entrySeparator + entry.getName();
                Path srcPath;
                if (entry.getSourceEntryName() != null) {
                    srcPath = retrieveService.getFile(
                            createRetrieveContext(entry.getSourceStorageSystemGroupID(),
                                    entry.getSourceStorageSystemID()), entry.getSourceName(),
                            entry.getSourceEntryName());
                } else {
                    srcPath = entry.getSourcePath();
                }
                storageService.storeFile(storageCtx, srcPath, entryName);
                LOG.info("Stored container entry: {} to {}@{}", entry.getSourcePath(), entryName,
                        storageSystem);
                entryNames.add(entryName);
                entry.setNotInContainerName(entryName);
            } catch (Exception e) {
                for (String n : entryNames) {
                    try {
                        storageService.deleteObject(storageCtx, n);
                    } catch (Exception e1) {
                        LOG.warn("Failed to delete  {}@{}", n, storageSystem, e1);
                    }
                }
                throw e;
            }
        }
    }

    private RetrieveContext createRetrieveContext(String storageSystemGroupID,
            String storageSystemID) {
        StorageSystem storageSystem = storageDeviceExtension().getStorageSystem(
                storageSystemGroupID, storageSystemID);
        return retrieveService.createRetrieveContext(storageSystem);
    }
}
