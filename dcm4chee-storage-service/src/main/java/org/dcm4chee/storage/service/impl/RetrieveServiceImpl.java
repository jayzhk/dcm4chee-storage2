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

package org.dcm4chee.storage.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.dcm4che3.net.Device;
import org.dcm4che3.util.SafeClose;
import org.dcm4che3.util.TagUtils;
import org.dcm4chee.storage.ContainerEntry;
import org.dcm4chee.storage.ExtractTask;
import org.dcm4chee.storage.ObjectNotFoundException;
import org.dcm4chee.storage.RetrieveContext;
import org.dcm4chee.storage.conf.StorageDevice;
import org.dcm4chee.storage.conf.StorageDeviceExtension;
import org.dcm4chee.storage.conf.StorageSystem;
import org.dcm4chee.storage.service.RetrieveService;
import org.dcm4chee.storage.service.VerifyContainerException;
import org.dcm4chee.storage.spi.ContainerProvider;
import org.dcm4chee.storage.spi.FileCacheProvider;
import org.dcm4chee.storage.spi.StorageSystemProvider;

/**
 * @author Gunter Zeilinger<gunterze@gmail.com>
 * @author Steve Kroetsch<stevekroetsch@hotmail.com>
 *
 */
@ApplicationScoped
public class RetrieveServiceImpl implements RetrieveService {

    @Inject @StorageDevice
    private Device device;

    @Inject
    private Instance<StorageSystemProvider> storageSystemProviders;

    @Inject
    private Instance<ContainerProvider> containerProviders;

    @Inject
    private Instance<FileCacheProvider> fileCacheProviders;

    private final ConcurrentHashMap<ExtractTaskKey, ExtractTask> extractTasks =
            new ConcurrentHashMap<ExtractTaskKey, ExtractTask>();

    public StorageSystem getStorageSystem(String groupID, String systemID) {
        StorageDeviceExtension devExt =
                device.getDeviceExtension(StorageDeviceExtension.class);
        return devExt.getStorageSystem(groupID, systemID);
    }

    @Override
    public RetrieveContext createRetrieveContext(StorageSystem storageSystem) {
        RetrieveContext ctx = new RetrieveContext();
        ctx.setStorageSystemProvider(
                storageSystem.getStorageSystemProvider(storageSystemProviders));
        ctx.setContainerProvider(
                storageSystem.getContainerProvider(containerProviders));
        ctx.setFileCacheProvider(
                storageSystem.getFileCacheProvider(fileCacheProviders));
        ctx.setStorageSystem(storageSystem);
        return ctx;
    }

    @Override
    public InputStream openInputStream(final RetrieveContext ctx, String name)
            throws IOException {
        InputStream in;
        if (ctx.getFileCacheProvider() != null) {
            in = Files.newInputStream(getFile(ctx, name));
        } else {
            StorageSystemProvider provider = ctx.getStorageSystemProvider();
            in = provider.openInputStream(ctx, name);
        }

        String digestAlgorithm = ctx.getStorageSystem().getStorageSystemGroup().getDigestAlgorithm();
        if (digestAlgorithm != null) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance(digestAlgorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("Invalid digest algorithm,"
                        + " check configuration for storage group "
                        + ctx.getStorageSystem().getStorageSystemGroup().getGroupID());
            }
            in = new DigestInputStream(in, digest) {
                @Override
                public void close() throws IOException {
                    super.close();
                    ctx.setDigest(TagUtils.toHexString(getMessageDigest().digest()));
                }
            };
        }

        return in;
    }

    @Override
    public InputStream openInputStream(RetrieveContext ctx, String name,
            String entryName) throws IOException, InterruptedException {
        if (ctx.getFileCacheProvider() != null)
            return Files.newInputStream(getFile(ctx, name, entryName));

        ContainerProvider containerProvider = ctx.getContainerProvider();
        if (containerProvider == null)
            throw new UnsupportedOperationException();

        StorageSystemProvider provider = ctx.getStorageSystemProvider();
        InputStream in = provider.openInputStream(ctx, name);
        try {
            return containerProvider.seekEntry(ctx, name, entryName, in);
        } catch (IOException e) {
            SafeClose.close(in);
            throw e;
        }
    }

    @Override
    public Path getFile(RetrieveContext ctx, String name) throws IOException {
        StorageSystemProvider provider = ctx.getStorageSystemProvider();
        FileCacheProvider fileCacheProvider = ctx.getFileCacheProvider();
        if (fileCacheProvider == null)
            return provider.getFile(ctx, name);

        Path path = fileCacheProvider.toPath(ctx, name);
        if (fileCacheProvider.access(path))
            return path;

        try ( InputStream in = provider.openInputStream(ctx, name) ) {
            Files.createDirectories(path.getParent());
            Files.copy(in, path);
        }
        fileCacheProvider.register(ctx, name, path);
        return path;
    }

    @Override
    public Path getFile(RetrieveContext ctx, String name, String entryName)
            throws IOException, InterruptedException {
        ContainerProvider containerProvider = ctx.getContainerProvider();
        if (containerProvider == null)
            throw new UnsupportedOperationException();

        FileCacheProvider fileCacheProvider = ctx.getFileCacheProvider();
        if (fileCacheProvider == null)
            throw new UnsupportedOperationException();

        Path path = fileCacheProvider.toPath(ctx, name).resolve(entryName);
        if (fileCacheProvider.access(path))
            return path;

        if (getExtractTask(ctx, name).getFile(entryName) == null
                && !fileCacheProvider.access(path))
            throw new ObjectNotFoundException(
                    ctx.getStorageSystem().getStorageSystemPath(), name, entryName);

        return path;
    }

    private ExtractTask getExtractTask(final RetrieveContext ctx, final String name) {
        final ExtractTaskKey key = new ExtractTaskKey(ctx.getStorageSystem(), name);
        final ExtractTask newTask = new ExtractTaskImpl(ctx, name);
        ExtractTask prevTask = extractTasks.putIfAbsent(key, newTask);
        if (prevTask != null)
            return prevTask;

        device.execute(new Runnable(){

            @Override
            public void run() {
                try (InputStream in = ctx.getStorageSystemProvider()
                        .openInputStream(ctx, name)) {
                    ctx.getContainerProvider().extractEntries(ctx, name,
                            newTask, in);
                } catch (IOException ex) {
                    newTask.exception(ex);
                }
                newTask.finished();
                extractTasks.remove(key);
            }});

        return newTask;
    }

    @Override
    public void verifyContainer(RetrieveContext ctx, String name,
            List<ContainerEntry> expectedEntries) throws IOException,
            VerifyContainerException {
        ContainerProvider archiverProvider = ctx.getContainerProvider();
        if (archiverProvider == null)
            throw new UnsupportedOperationException();

        StorageSystemProvider provider = ctx.getStorageSystemProvider();
        InputStream in = provider.openInputStream(ctx, name);
        TestExtractTask extractTask = new TestExtractTask();
        try {
            archiverProvider.extractEntries(ctx, name, extractTask, in);
        } catch (IOException e) {
            throw new VerifyContainerException("Extract failed for " + name, e);
        } finally {
            SafeClose.close(in);
        }

        List<String> entryNames = extractTask.getEntryNames();
        for (ContainerEntry entry : expectedEntries)
            if (!entryNames.contains(entry.getName()))
                throw new VerifyContainerException("Missing container entry: "
                        + entry.getName() + " from " + name);
    }

    @Override
    public void resolveContainerEntries(List<ContainerEntry> entries) throws IOException,
            InterruptedException {
        for (ContainerEntry entry : entries) {
            if (entry.getSourceName() != null && entry.getSourceStorageSystemID() != null
                    && entry.getSourceStorageSystemGroupID() != null) {
                StorageSystem storageSystem = getStorageSystem(
                        entry.getSourceStorageSystemGroupID(),
                        entry.getSourceStorageSystemID());
                if (storageSystem == null) {
                    throw new IllegalStateException(
                            "StorageSystem not found for Source! StorageSystemGroupID="
                                    + entry.getSourceStorageSystemGroupID()
                                    + "StorageSystemID="
                                    + entry.getSourceStorageSystemID());
                }
                RetrieveContext retrieveCtx = createRetrieveContext(storageSystem);
                Path path = entry.getSourceEntryName() == null ? getFile(retrieveCtx,
                        entry.getSourceName()) : getFile(retrieveCtx,
                        entry.getSourceName(), entry.getSourceEntryName());
                entry.setSourcePath(path);
            } else if (entry.getSourcePath() == null)
                throw new IllegalStateException(
                        "Source path could not be resolved for container entry: " + entry);
        }
    }

    @Override
    public boolean calculateDigestAndMatch(RetrieveContext ctx, String digest, String name)
            throws IOException {

        try (InputStream in = openInputStream(ctx, name)) {
            byte[] buffer = new byte[1024];
            // read fully just to calculate digest
            while (in.read(buffer) != -1)
                ;
        }

        String calculatedDigest = ctx.getDigest();

        return calculatedDigest != null && calculatedDigest.equals(digest);
    }

    @Override
    public <E extends Enum<E>> E queryStatus(RetrieveContext ctx, String name,
            Class<E> enumType) throws IOException {
        return ctx.getStorageSystemProvider().queryStatus(ctx, name, enumType);
    }

}
