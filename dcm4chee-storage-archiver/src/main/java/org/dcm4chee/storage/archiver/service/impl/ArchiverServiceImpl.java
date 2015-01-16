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
import java.util.List;

import javax.annotation.Resource;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;

import org.dcm4che3.net.Device;
import org.dcm4chee.storage.ContainerEntry;
import org.dcm4chee.storage.RetrieveContext;
import org.dcm4chee.storage.StorageContext;
import org.dcm4chee.storage.archiver.service.ContainerEntriesStored;
import org.dcm4chee.storage.archiver.service.ArchiverContext;
import org.dcm4chee.storage.archiver.service.ArchiverService;
import org.dcm4chee.storage.conf.Archiver;
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
public class ArchiverServiceImpl implements ArchiverService {

    private static final Logger LOG = LoggerFactory
            .getLogger(ArchiverServiceImpl.class);

    @Resource(mappedName = "java:/ConnectionFactory")
    private ConnectionFactory connFactory;

    @Resource(mappedName = "java:/queue/archiver")
    private Queue archiverQueue;

    @Inject
    private StorageService storageService;

    @Inject
    private RetrieveService retrieveService;

    @Inject
    private Device device;

    @Inject
    @ContainerEntriesStored
    private Event<ArchiverContext> containerStored;

    @Override
    public ArchiverContext createContext(String groupID, String name,
            String digestAlgorithm) {
        ArchiverContext context = new ArchiverContext();
        context.setGroupID(groupID);
        context.setName(name);
        context.setDigestAlgorithm(digestAlgorithm);
        return context;
    }

    @Override
    public void scheduleStore(ArchiverContext context) throws IOException {
        scheduleStore(context, 0, 0);
    }

    private void scheduleStore(ArchiverContext context, int retries, long delay) {
        try {
            Connection conn = connFactory.createConnection();
            try {
                Session session = conn.createSession(false,
                        Session.AUTO_ACKNOWLEDGE);
                MessageProducer producer = session
                        .createProducer(archiverQueue);
                ObjectMessage msg = session.createObjectMessage(context);
                msg.setIntProperty("Retries", retries);
                if (delay > 0)
                    msg.setLongProperty("_HQ_SCHED_DELIVERY",
                            System.currentTimeMillis() + delay);
                producer.send(msg);
            } finally {
                conn.close();
            }
        } catch (JMSException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void store(ArchiverContext context, int retries) {
        try {
            StorageSystem storageSystem = selectStorageSystem(context);
            makeContainer(storageSystem, context.getEntries(), context.getName(),
                    context.getDigestAlgorithm());
            context.setStorageSystemID(storageSystem.getStorageSystemID());
            containerStored.fire(context);
        } catch (Exception e) {
            String groupID = context.getGroupID();
            StorageDeviceExtension ext = device
                    .getDeviceExtension(StorageDeviceExtension.class);
            Archiver archiver = ext.getArchiver();
            if (archiver != null && retries < ext.getArchiver().getMaxRetries()) {
                int delay = archiver.getRetryInterval();
                LOG.warn(
                        "Failed to store container entries to Storage System Group {} - retry in {}s:",
                        groupID, retries, e);
                scheduleStore(context, retries + 1, delay * 1000L);
            } else {
                LOG.error(
                        "Failed to store container entries to Storage System Group {}",
                        groupID, e);
            }
            throw new RuntimeException(e);
        }
    }

    private StorageSystem selectStorageSystem(ArchiverContext context)
            throws IOException {
        long reserveSpace = 0L;
        for (ContainerEntry entry : context.getEntries())
            reserveSpace += Files.size(entry.getPath());

        String groupID = context.getGroupID();
        StorageSystem storageSystem = storageService.selectStorageSystem(
                groupID, reserveSpace);
        if (storageSystem == null)
            throw new IOException(
                    "No writeable Storage System in Storage System Group "
                            + groupID);
        return storageSystem;
    }

    private void makeContainer(StorageSystem storageSystem,
            List<ContainerEntry> entries, String name, String digestAlgorithm)
            throws Exception {
        StorageContext storageCtx = storageService
                .createStorageContext(storageSystem);
        try {
            storageService.storeContainerEntries(storageCtx, entries, name);
            RetrieveContext retrieveCtx = retrieveService
                    .createRetrieveContext(storageSystem);
            retrieveCtx.setDigestAlgorithm(digestAlgorithm);
            StorageDeviceExtension ext = device
                    .getDeviceExtension(StorageDeviceExtension.class);
            Archiver archiver = ext.getArchiver();
            if (archiver != null && archiver.isVerifyContainer())
                retrieveService.verifyContainer(retrieveCtx, name, entries);
            LOG.info("Stored container entries: {} to {}@{}", entries.size(),
                    entries, name, storageSystem);
        } catch (Exception e) {
            try {
                storageService.deleteObject(storageCtx, name);
            } catch (IOException e1) {
                LOG.warn("Failed to delete container {}@{}", name, storageSystem,
                        e1);
            }
            throw e;
        }
    }
}