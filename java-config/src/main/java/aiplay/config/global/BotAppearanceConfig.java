package aiplay.config.global;

/**
 * Visual appearance for a bot. All four fields are required (no fallback) so
 * every bot in {@code gameplay.json} has a deterministic look.
 *
 * @param meshClass Botpack pawn class that supplies the mesh (and the matching
 *                  skin package). One of: {@code TMale1Bot}, {@code TMale2Bot},
 *                  {@code TFemale1Bot}, {@code TFemale2Bot}.
 * @param skin      Full skin texture spec: {@code <SkinPackage>.<skinCode>}.
 *                  Examples: {@code CommandoSkins.cmdo}, {@code SoldierSkins.blkt},
 *                  {@code SGirlSkins.fbth}, {@code FCommandoSkins.daco}.
 * @param face      Full face texture spec: {@code <SkinPackage>.<FaceName>}.
 *                  Example: {@code CommandoSkins.Blake}, {@code SGirlSkins.Aryss}.
 * @param voice     Full voice pack class path: {@code <Package>.<ClassName>}.
 *                  Example: {@code BotPack.VoiceMaleTwo}.
 */
public record BotAppearanceConfig(
    String meshClass,
    String skin,
    String face,
    String voice
) {
  public BotAppearanceConfig {
    if (meshClass == null || meshClass.isBlank()) {
      throw new IllegalArgumentException("BotAppearanceConfig.meshClass is required");
    }
    if (skin == null || skin.isBlank()) {
      throw new IllegalArgumentException("BotAppearanceConfig.skin is required");
    }
    if (face == null || face.isBlank()) {
      throw new IllegalArgumentException("BotAppearanceConfig.face is required");
    }
    if (voice == null || voice.isBlank()) {
      throw new IllegalArgumentException("BotAppearanceConfig.voice is required");
    }
  }
}
