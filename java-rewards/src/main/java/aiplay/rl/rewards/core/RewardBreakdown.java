package aiplay.rl.rewards.core;

/**
 * Per-tick reward breakdown: één scalar-waarde per {@link RewardSignal}, plus de
 * scalar {@link #total()}.
 *
 * <p>Intern een {@code double[]} geïndexeerd op {@link RewardSignal#ordinal()} —
 * de signaal-catalog is de single source of truth. Consumenten (decompositie,
 * window-logging, diagnostics) lezen {@link #value(RewardSignal)} of itereren
 * over {@link RewardSignal#values()}; producenten vullen via de {@link Builder}
 * (signaal-keyed, volgorde-onafhankelijk). Er zijn bewust géén positionele
 * constructie en geen per-veld accessors meer: die spiegelden de volledige
 * signaal-lijst op elke plek en dreven uiteen zodra een reward werd toegevoegd.</p>
 */
public final class RewardBreakdown {

  private final double[] values;
  private final double total;

  private RewardBreakdown(double[] values, double total) {
    this.values = values;
    this.total = total;
  }

  /** Waarde van één signaal. */
  public double value(RewardSignal signal) {
    return values[signal.ordinal()];
  }

  /** Scalar-totaal over alle signalen. */
  public double total() {
    return total;
  }

  /** Som van alle signalen in {@code category} — gebruikt voor logging-subtotalen. */
  public double categoryTotal(RewardCategory category) {
    double sum = 0.0;
    for (RewardSignal s : RewardSignal.values()) {
      if (s.category() == category) {
        sum += values[s.ordinal()];
      }
    }
    return sum;
  }

  /** Lege breakdown met enkel een scalar-totaal (death/pre-alive edge-cases). */
  public static RewardBreakdown zero(double total) {
    return new RewardBreakdown(new double[RewardSignal.COUNT], total);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Keyed builder — de enige manier om een gevulde breakdown te maken. Single-use:
   * na {@link #build(double)} / {@link #build()} wordt het interne array overgedragen
   * (geen kopie).
   */
  public static final class Builder {
    private final double[] values = new double[RewardSignal.COUNT];

    /** Zet (overschrijft) de waarde van één signaal. */
    public Builder set(RewardSignal signal, double value) {
      values[signal.ordinal()] = value;
      return this;
    }

    /** Telt {@code value} op bij het signaal (voor producenten die meerdere keren bijdragen). */
    public Builder add(RewardSignal signal, double value) {
      values[signal.ordinal()] += value;
      return this;
    }

    /** Bouw met expliciete scalar — gebruik wanneer de producent het totaal al precies kent. */
    public RewardBreakdown build(double total) {
      return new RewardBreakdown(values, total);
    }

    /** Bouw met het totaal als som van alle signalen. */
    public RewardBreakdown build() {
      double t = 0.0;
      for (double v : values) {
        t += v;
      }
      return new RewardBreakdown(values, t);
    }
  }
}
