package aiplay.recordlauncher.json;

import aiplay.config.global.GlobalConfigRepository;
import org.json.JSONArray;
import org.json.JSONObject;

public class RecorderService {

    private final String recordingPlayerName;

    public RecorderService() {
        this.recordingPlayerName = GlobalConfigRepository.shared().recording().playerName();
    }

    public RecorderModel createGameStatus(String jsonOutputString) {
        RecorderModel gameStatus = new RecorderModel();
        gameStatus.setJsonString(jsonOutputString);

        JSONObject root = new JSONObject(jsonOutputString);
        JSONArray players = root.getJSONArray("Players");
        JSONObject mapInfo = root.getJSONObject("MapInfo");

        gameStatus.setElapsedTime(mapInfo.getDouble("ElapsedTime"));
        gameStatus.setHasFlag(0); // default

        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.getJSONObject(i);
            String playerName = player.getString("Name");

            if (recordingPlayerName.equalsIgnoreCase(playerName)) {
                // In UT99 PlayerReplicationInfo.HasFlag is an Actor ref to the carried CTFFlag,
                // or None when the player carries nothing. Serialized as "<package>.<flagActor>"
                // or "None". Any non-empty, non-"None" value means the player is carrying a flag.
                String flagRef = player.optString("HasFlag", "");
                boolean hasFlag = !flagRef.isEmpty() && !"None".equalsIgnoreCase(flagRef);
                gameStatus.setHasFlag(hasFlag ? 1 : 0);
            }
        }

        return gameStatus;
    }
}
