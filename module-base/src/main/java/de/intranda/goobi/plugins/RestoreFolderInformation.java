package de.intranda.goobi.plugins;

import lombok.Data;

@Data
public class RestoreFolderInformation {
    private int processId;
    private int foldersToRestore;
    private int foldersRestored;
    private int imagesToRestore;
    private int imagesRestored;
    private String errorMessage;

    public RestoreFolderInformation(int processId) {
        super();
        this.processId = processId;
    }

}
