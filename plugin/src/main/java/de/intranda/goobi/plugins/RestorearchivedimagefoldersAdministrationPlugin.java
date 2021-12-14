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
    private List<RestoreFolderInformation> restoreInfos = new ArrayList<RestoreFolderInformation>();

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

    public void execute() {
        String query = FilterHelper.criteriaBuilder(filter, false, null, null, null, true, false);
        List<Integer> tempProcesses = ProcessManager.getIDList(query);

        restoreInfos = tempProcesses.stream()
                .map(id -> new RestoreFolderInformation(id))
                .collect(Collectors.toList());

        Runnable runnable = () -> {
            for (RestoreFolderInformation restoreInfo : restoreInfos) {
                currentlyRestoring = restoreInfo;
                List<Path> archiveInformationFiles = getArchiveInformationFilesForProcess(restoreInfo.getProcessId());
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
                if (Instant.now().isAfter(lastPush.plus(500, ChronoUnit.MILLIS))) {
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

        client.connect(xmlConf.getString("host"));
        client.authPublickey(xmlConf.getString("user"));
        return client;
    }

    private List<Path> getArchiveInformationFilesForProcess(Integer processId) {
        Path imagesPath = Paths.get(ConfigurationHelper.getInstance().getGoobiFolder(), "metadata", processId.toString(), "images");
        try (Stream<Path> fileStream = Files.list(imagesPath)) {
            return fileStream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".xml"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error(e);
            return new ArrayList<Path>();
        }
    }

    @Override
    public void setPushContext(PushContext pusher) {
        this.pusher = pusher;

    }
}
