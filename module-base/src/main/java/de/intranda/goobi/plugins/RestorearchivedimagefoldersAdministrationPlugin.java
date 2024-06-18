package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.goobi.production.enums.PluginType;
import org.goobi.production.flow.statistics.hibernate.FilterHelper;
import org.goobi.production.plugin.interfaces.IAdministrationPlugin;
import org.goobi.production.plugin.interfaces.IPushPlugin;
import org.omnifaces.cdi.PushContext;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.persistence.managers.ProcessManager;
import io.goobi.workflow.api.connection.SftpUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class RestorearchivedimagefoldersAdministrationPlugin implements IAdministrationPlugin, IPushPlugin {

    private static final long serialVersionUID = -4556219498290440568L;

    private PushContext pusher;

    @Getter
    private String title = "intranda_administration_restorearchivedimagefolders";

    @Getter
    private boolean run;

    @Getter
    @Setter
    private String filter;

    @Getter
    @Setter
    private int totalImagesToRestore;
    @Getter
    @Setter
    private int totalImagesRestored;
    @Getter
    @Setter
    private int percentDone;

    @Getter
    private transient List<RestoreFolderInformation> restoreInfos = new ArrayList<>();

    @Getter
    private transient RestoreFolderInformation currentlyRestoring;

    @Override
    public PluginType getType() {
        return PluginType.Administration;
    }

    @Override
    public String getGui() {
        return "/uii/plugin_administration_restorearchivedimagefolders.xhtml";
    }

    public void execute() throws ConfigurationException {
        String query = FilterHelper.criteriaBuilder(filter, false, null, null, null, true, false);
        List<Integer> tempProcesses = ProcessManager.getIdsForFilter(query);

        restoreInfos = tempProcesses.stream()
                .map(id -> new RestoreFolderInformation(id))
                .collect(Collectors.toList());
        for (RestoreFolderInformation restoreInfo : restoreInfos) {
            List<Path> archiveInformationFiles = getArchiveInformationFilesForProcess(restoreInfo.getProcessId());
            int numberOfImages = 0;
            for (Path archiveInformationFile : archiveInformationFiles) {
                XMLConfiguration xmlConf = new XMLConfiguration(archiveInformationFile.toFile());
                numberOfImages += xmlConf.getInt("numberOfImages", 0);
            }
            totalImagesToRestore += numberOfImages;
            restoreInfo.setImagesToRestore(numberOfImages);
        }
        Runnable runnable = () -> {
            for (RestoreFolderInformation restoreInfo : restoreInfos) {
                List<Path> archiveInformationFiles = getArchiveInformationFilesForProcess(restoreInfo.getProcessId());
                currentlyRestoring = restoreInfo;
                for (Path archiveInformationFile : archiveInformationFiles) {
                    try {
                        restoreFolder(restoreInfo, archiveInformationFile);
                    } catch (ConfigurationException | IOException e) {
                        log.error(e);
                        continue;
                    }
                    try {
                        Files.delete(archiveInformationFile);
                    } catch (IOException e) {
                        log.error(e);
                    }
                }
            }
            pusher.send("update");
        };
        new Thread(runnable).start();
    }

    private void restoreFolder(RestoreFolderInformation info, Path archiveInformationFile) throws ConfigurationException, IOException {
        Instant lastPush = Instant.now();
        XMLConfiguration xmlConf = new XMLConfiguration(archiveInformationFile.toFile());
        try (SftpUtils sftpClient = new SftpUtils(xmlConf.getString("user"), xmlConf.getString("privateKeyLocation"),
                xmlConf.getString("privateKeyPassphrase"), xmlConf.getString("host"), xmlConf.getInt("port"), xmlConf.getString("knownHostsFile"))) {

            Path remotePath =
                    Paths.get(Integer.toString(info.getProcessId()), "images", archiveInformationFile.getFileName().toString().replace(".xml", ""));
            Path localPath = Paths.get(ConfigurationHelper.getInstance().getGoobiFolder(), "metadata").resolve(remotePath);
            Files.createDirectories(localPath);

            sftpClient.changeRemoteFolder(remotePath.toString());
            List<String> remoteFiles = sftpClient.listContent();

            info.setImagesToRestore(remoteFiles.size() - 2);
            for (String remoteFile : remoteFiles) {
                if (".".equals(remoteFile) || "..".equals(remoteFile)) {
                    continue;
                }
                sftpClient.downloadFile(remoteFile, localPath);
                info.setImagesRestored(info.getImagesRestored() + 1);
                totalImagesRestored++;
                percentDone = (int) (((double) totalImagesRestored / (double) totalImagesToRestore) * 100);
                if (Instant.now().isAfter(lastPush.plus(500, ChronoUnit.MILLIS))) {
                    lastPush = Instant.now();
                    pusher.send("update");
                }
            }
            for (String remoteFile : remoteFiles) {
                if (".".equals(remoteFile) || "..".equals(remoteFile)) {
                    continue;
                }
                sftpClient.deleteFile(remoteFile);
            }
            sftpClient.changeRemoteFolder("..");
            sftpClient.deleteFolder(remotePath.getFileName().toString());
            sftpClient.changeRemoteFolder("..");
            sftpClient.deleteFolder(remotePath.getParent().getFileName().toString());
            sftpClient.changeRemoteFolder("..");
            sftpClient.deleteFolder(remotePath.getParent().getParent().getFileName().toString());
        }
    }

    private List<Path> getArchiveInformationFilesForProcess(Integer processId) {
        Path imagesPath = Paths.get(ConfigurationHelper.getInstance().getGoobiFolder(), "metadata", processId.toString(), "images");
        try (Stream<Path> fileStream = Files.list(imagesPath)) {
            return fileStream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".xml"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error(e);
            return new ArrayList<>();
        }
    }

    @Override
    public void setPushContext(PushContext pusher) {
        this.pusher = pusher;

    }
}
