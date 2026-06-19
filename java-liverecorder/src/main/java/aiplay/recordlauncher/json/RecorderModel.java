package aiplay.recordlauncher.json;

public class RecorderModel {

    private String jsonString;
    private double elapsedTime;
    private int hasFlag;

    public String getJsonString() {
        return jsonString;
    }

    public void setJsonString(String jsonString) {
        this.jsonString = jsonString;
    }

    public double getElapsedTime() {
        return elapsedTime;
    }

    public void setElapsedTime(double elapsedTime) {
        this.elapsedTime = elapsedTime;
    }

    public int getHasFlag() {
        return hasFlag;
    }

    public void setHasFlag(int hasFlag) {
        this.hasFlag = hasFlag;
    }
}
