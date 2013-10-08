package de.intranda.goobi.plugins;

public class ImageInformation {

    private String physicalNumber = "";
    private String logicalNumber = "";
    private String imageName = "";
    private String type = "";

    public ImageInformation(String physicalNumber, String logicalNumber, String imageName, String type) {
        this.physicalNumber = physicalNumber;
        this.logicalNumber = logicalNumber;
        this.imageName = imageName;
        this.type = type;
    }

    public String getPhysicalNumber() {
        return physicalNumber;
    }

    public String getLogicalNumber() {
        return logicalNumber;
    }

    public String getImageName() {
        return imageName;
    }

    public String getType() {
        return type;
    }

    public void setPhysicalNumber(String physicalNumber) {
        this.physicalNumber = physicalNumber;
    }

    public void setLogicalNumber(String logicalNumber) {
        this.logicalNumber = logicalNumber;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "ImageInformation [physicalNumber=" + physicalNumber + ", logicalNumber=" + logicalNumber + ", imageName=" + imageName + ", type="
                + type + "]";
    }

}
