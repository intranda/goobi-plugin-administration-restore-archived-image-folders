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
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class RestorearchivedimagefoldersAdministrationPlugin implements IAdministrationPlugin, IPushPlugin {
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
    private List<RestoreFolderInformation> restoreInfos = new ArrayList<>();

    @Getter
    private RestoreFolderInformation currentlyRestoring;

    @Override
    public PluginType getType() {
        return PluginType.Administration;
    }

    @Override
    public String getGui() {
        return "/uii/plugin_administration_restorearchivedimagefolders.xhtml";
    }

    /**
     * Constructor
     */
    public RestorearchivedimagefoldersAdministrationPlugin() {
        log.info("Sample admnistration plugin started");
    }

    public void execute() throws ConfigurationException {
        //        log.info("Starting to excute.");
        String query = FilterHelper.criteriaBuilder(filter, false, null, null, null, true, false);
        List<Integer> tempProcesses = ProcessManager.getIdsForFilter(query);

        restoreInfos = tempProcesses.stream()
                .map(id -> new RestoreFolderInformation(id))
                .collect(Collectors.toList());
        //        log.info("The length of restoreInfos is :" + restoreInfos.size());
        for (RestoreFolderInformation restoreInfo : restoreInfos) {
            List<Path> archiveInformationFiles = getArchiveInformationFilesForProcess(restoreInfo.getProcessId());
            //            log.info("The length of archiveInformationFiles is :" + archiveInformationFiles.size());
            int numberOfImages = 0;
            for (Path archiveInformationFile : archiveInformationFiles) {
                XMLConfiguration xmlConf = new XMLConfiguration(archiveInformationFile.toFile());
                numberOfImages += xmlConf.getInt("numberOfImages", 0);
                //                log.info("numberOfImages is :" + numberOfImages);
            }
            totalImagesToRestore += numberOfImages;
            restoreInfo.setImagesToRestore(numberOfImages);
        }
        //        log.info("The number of images to restore is :" + totalImagesToRestore);
        Runnable runnable = () -> {
            for (RestoreFolderInformation restoreInfo : restoreInfos) {
                List<Path> archiveInformationFiles = getArchiveInformationFilesForProcess(restoreInfo.getProcessId());
                currentlyRestoring = restoreInfo;
                for (Path archiveInformationFile : archiveInformationFiles) {
                    try {
                        restoreFolder(restoreInfo, archiveInformationFile);
                    } catch (ConfigurationException | IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                        continue;
                    }
                    try {
                        Files.delete(archiveInformationFile);
                    } catch (IOException e) {
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
        try (SSHClient sshClient = createSSHClient(xmlConf); SFTPClient sftpClient = sshClient.newSFTPClient()) {
            Path remotePath =
                    Paths.get(Integer.toString(info.getProcessId()), "images", archiveInformationFile.getFileName().toString().replace(".xml", ""));
            Path localPath = Paths.get(ConfigurationHelper.getInstance().getGoobiFolder(), "metadata").resolve(remotePath);
            Files.createDirectories(localPath);
            List<RemoteResourceInfo> remoteFiles = sftpClient.ls(remotePath.toString());
            info.setImagesToRestore(remoteFiles.size());
            for (RemoteResourceInfo remoteFile : remoteFiles) {
                sftpClient.get(remoteFile.getPath(), localPath.resolve(remoteFile.getName()).toString());
                info.setImagesRestored(info.getImagesRestored() + 1);
                totalImagesRestored++;
                percentDone = (int) (((double) totalImagesRestored / (double) totalImagesToRestore) * 100);
                if (Instant.now().isAfter(lastPush.plus(500, ChronoUnit.MILLIS))) {
                    lastPush = Instant.now();
                    pusher.send("update");
                }
            }
            for (RemoteResourceInfo remoteFile : remoteFiles) {
                sftpClient.rm(remoteFile.getPath());
            }
            sftpClient.rmdir(remotePath.toString());
            sftpClient.rmdir(remotePath.getParent().toString());
            sftpClient.rmdir(remotePath.getParent().getParent().toString());
        }

    }

    private SSHClient createSSHClient(XMLConfiguration xmlConf) throws IOException {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(new PromiscuousVerifier());
        try {
            log.info("Start to connect");
            client.connect(xmlConf.getString("host"));
            //            log.info("host is :" + xmlConf.getString("host"));
            //            client.authPublickey(xmlConf.getString("user"));
            KeyProvider kp = client.loadKeys(xmlConf.getString("privateKeyLocation"), xmlConf.getString("privateKeyPassphrase"));
            client.authPublickey(xmlConf.getString("user"), kp);
        } catch (net.schmizz.sshj.userauth.UserAuthException e) {
            log.error(e);
        }
        return client;
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
