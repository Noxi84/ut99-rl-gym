// Persistent Info actor that exposes server-side iterators to the
// NeuralNetWebserver WebApplication. A WebApplication is not an Actor
// and therefore cannot call iterator functions like AllActors() directly.
//
// During RL training the helper is bypassed: RLCTFGame.GetProjectilesJson()
// is used. During human recording the gametype is Botpack.CTFGame and the
// cast to RLCTFGame fails, so NeuralNetWebserver lazy-spawns this helper
// on first use and routes the iteration through here instead.
class RLNeuralNetHelper extends Info;

function string GetProjectilesJson() {
    local Projectile P;
    local bool first;
    local string msg;
    local string instigatorName;
    local int instigatorTeam;

    first = true;
    foreach AllActors(class'Projectile', P) {
        if (!first) msg $= ",";
        instigatorName = "";
        instigatorTeam = -1;
        if (P.Instigator != None && P.Instigator.PlayerReplicationInfo != None) {
            instigatorName = P.Instigator.PlayerReplicationInfo.PlayerName;
            instigatorTeam = int(P.Instigator.PlayerReplicationInfo.Team);
        }
        msg $= "{";
        msg $= "\"Class\":\"" $ string(P.Class) $ "\",";
        msg $= "\"Location\":\"" $ string(P.Location) $ "\",";
        msg $= "\"Velocity\":\"" $ string(P.Velocity) $ "\",";
        msg $= "\"Speed\":\"" $ string(P.Speed) $ "\",";
        msg $= "\"Damage\":\"" $ string(P.Damage) $ "\",";
        msg $= "\"InstigatorName\":\"" $ instigatorName $ "\",";
        msg $= "\"InstigatorTeam\":\"" $ string(instigatorTeam) $ "\"";
        msg $= "}";
        first = false;
    }
    return msg;
}

defaultproperties
{
    bHidden=true
    bAlwaysRelevant=false
    RemoteRole=ROLE_None
}
