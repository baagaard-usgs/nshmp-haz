package org.opensha2.gmm;

import static org.opensha2.gmm.GmmInput.Field.MW;
import static org.opensha2.gmm.GmmInput.Field.RJB;
import static org.opensha2.gmm.GmmInput.Field.VS30;

import org.opensha2.calc.ExceedanceModel;
import org.opensha2.data.Data;
import org.opensha2.gmm.GmmInput.Constraints;
import org.opensha2.gmm.GroundMotionTables.GroundMotionTable;
import org.opensha2.gmm.GroundMotionTables.GroundMotionTable.Position;
import org.opensha2.internal.MathUtils;

import com.google.common.collect.Range;
import com.google.common.primitives.Ints;

import java.util.Map;

/**
 * Experimental implementation of the PEER NGA-East ground motion model by
 * Goulet et al. (2016). This is a composite model that consists of 29 median
 * ground motion models with period dependent weights and a common standard
 * deviation model that itself consists of a 3-branch logic tree. A complete
 * NGA-East hazard curve is the wieghted sum of 87 individual curves.
 * 
 * <p>Calculation of hazard using this preliminary implementation deviates
 * somewhat from the current nshmp-haz PSHA pipeline and required implementation
 * of a {@code MultiScalarGroundMotion}. A {@code MultiScalarGroundMotion}
 * stores arrays of means and sigmas with associated weights and can only be
 * properly processed by {@link ExceedanceModel#NSHM_CEUS_MAX_INTENSITY} at this
 * time. NGA-East uses table lookups for the 29 component models.
 *
 * <p><b>Note:</b> Direct instantiation of {@code GroundMotionModel}s is
 * prohibited. Use {@link Gmm#instance(Imt)} to retrieve an instance for a
 * desired {@link Imt}.
 *
 * <p><b>Implementation note:</b> Mean values are clamped per
 * {@link GmmUtils#ceusMeanClip(Imt, double)}.
 *
 * <p><b>doi:</b> TODO
 *
 * <p><b>Reference:</b> Goulet, C., Bozorgnia, Y., Abrahamson, N.A., Kuehn, N.,
 * Al Atik L., Youngs, R., Graves, R., Atkinson, G., in review, Central and
 * Eastern North America Ground-Motion Characterization: NGA-East Final Report,
 * 665 p.
 *
 * <p><b>Component:</b> average horizontal (RotD50)
 *
 * @author Peter Powers
 * @see Gmm#ATKINSON_08_PRIME
 */
public abstract class NgaEast_2016 implements GroundMotionModel {

  /*
   * TODO
   * 
   * Cluster analysis is incorrect as currently implemented; analysis performed
   * after combining models
   * 
   * Deagg will currently use weight-averaged means; this is incorrect, or at
   * least requires more study to determine if it generates an acceptable
   * approximation
   */
  static final String NAME = "NGA-East: Goulet et al. (2016)";

  static final Constraints CONSTRAINTS = Constraints.builder()
      .set(MW, Range.closed(4.0, 8.2))
      .set(RJB, Range.closed(0.0, 1500.0))
      .set(VS30, Range.singleton(3000.0))
      .build();

  /*
   * Sigma coefficients for global model from tables 11-3 (tau) and 11-9 (phi).
   */
  static CoefficientContainer COEFFS_SIGMA_LO;
  static CoefficientContainer COEFFS_SIGMA_MID;
  static CoefficientContainer COEFFS_SIGMA_HI;
  static CoefficientContainer COEFFS_SIGMA_NGAW2;

  static {
    /*
     * TODO nga-east data are not public and therefore may not exist when
     * initializing Gmm's; we therefore init with a dummy file. Once data are
     * public, make fields (above) final, remove try-catch, and delete summy
     * sigma file.
     */
    try {
      COEFFS_SIGMA_LO = new CoefficientContainer("nga-east-sigma-lo.csv");
    } catch (Exception e) {
      COEFFS_SIGMA_LO = new CoefficientContainer("dummy-nga-east-sigma.csv");
    }
    try {
      COEFFS_SIGMA_MID = new CoefficientContainer("nga-east-sigma-mid.csv");
    } catch (Exception e) {
      COEFFS_SIGMA_MID = new CoefficientContainer("dummy-nga-east-sigma.csv");
    }
    try {
      COEFFS_SIGMA_HI = new CoefficientContainer("nga-east-sigma-hi.csv");
    } catch (Exception e) {
      COEFFS_SIGMA_HI = new CoefficientContainer("dummy-nga-east-sigma.csv");
    }
    try {
      COEFFS_SIGMA_NGAW2 = new CoefficientContainer("nga-east-sigma-ngaw2.csv");
    } catch (Exception e) {
      COEFFS_SIGMA_NGAW2 = new CoefficientContainer("dummy-nga-east-sigma.csv");
    }
  }

  private static final double[] SIGMA_WTS = { 0.185, 0.63, 0.185 };

  /* ModelID's of concentric Sammon's map rings. */
  private static final int[] R0 = { 1 };
  private static final int[] R1 = Data.indices(2, 5);
  private static final int[] R2 = Data.indices(6, 13);
  private static final int[] R3 = Data.indices(14, 29);

  private static final class Coefficients {

    final double a, b, τ1, τ2, τ3, τ4;

    Coefficients(Imt imt, CoefficientContainer cc) {
      Map<String, Double> coeffs = cc.get(imt);
      a = coeffs.get("a");
      b = coeffs.get("b");
      τ1 = coeffs.get("tau1");
      τ2 = coeffs.get("tau2");
      τ3 = coeffs.get("tau3");
      τ4 = coeffs.get("tau4");
    }
  }

  private static final class CoefficientsNgaw2 {

    final double m5, m6, m7;

    CoefficientsNgaw2(Imt imt, CoefficientContainer cc) {
      Map<String, Double> coeffs = cc.get(imt);
      m5 = coeffs.get("m5");
      m6 = coeffs.get("m6");
      m7 = coeffs.get("m7");
    }
  }

  private final Coefficients σCoeffsLo;
  private final Coefficients σCoeffsMid;
  private final Coefficients σCoeffsHi;
  private final CoefficientsNgaw2 σCoeffsNgaw2;

  private final GroundMotionTable[] tables;
  private final double[] weights;

  NgaEast_2016(final Imt imt) {
    σCoeffsLo = new Coefficients(imt, COEFFS_SIGMA_LO);
    σCoeffsMid = new Coefficients(imt, COEFFS_SIGMA_MID);
    σCoeffsHi = new Coefficients(imt, COEFFS_SIGMA_HI);
    σCoeffsNgaw2 = new CoefficientsNgaw2(imt, COEFFS_SIGMA_NGAW2);
    tables = GroundMotionTables.getNgaEast(imt);
    weights = GroundMotionTables.getNgaEastWeights(imt);
  }

  double[] calcSigmasTotal(double Mw) {
    return new double[] {
        calcSigma(σCoeffsLo, Mw),
        calcSigma(σCoeffsMid, Mw),
        calcSigma(σCoeffsHi, Mw),
    };
  }

  double[] calcSigmasMid(double Mw) {
    return new double[] {
        calcSigma(σCoeffsMid, Mw)
    };
  }

  private static double calcSigma(Coefficients c, double Mw) {

    /* Global τ model. Equation 10-6. */
    double τ = c.τ4;
    if (Mw <= 4.5) {
      τ = c.τ1;
    } else if (Mw <= 5.0) {
      τ = c.τ1 + (c.τ2 - c.τ1) * (Mw - 4.5) / 0.5;
    } else if (Mw <= 5.5) {
      τ = c.τ2 + (c.τ3 - c.τ2) * (Mw - 5.5) / 0.5;
    } else if (Mw <= 6.5) {
      τ = c.τ3 + (c.τ4 - c.τ3) * (Mw - 5.5);
    }

    /* Global φ model. Equation 11-9. */
    double φ = c.b;
    if (Mw <= 5.0) {
      φ = c.a;
    } else if (Mw <= 6.5) {
      φ = c.a + (Mw - 5.0) * (c.b - c.a) / 1.5;
    }

    return MathUtils.hypot(τ, φ);
  }

  double[] calcSigmasNgaw2(double Mw) {
    return new double[] {
        Mw < 5.5 ? σCoeffsNgaw2.m5 : Mw < 6.5 ? σCoeffsNgaw2.m6 : σCoeffsNgaw2.m7
    };
  }

  /* Return the subset of weights specified by the supplied modelIDs. */
  private static double[] selectWeights(double[] weights, int[] modelIDs) {
    double[] subsetWeights = new double[modelIDs.length];
    for (int i = 0; i < modelIDs.length; i++) {
      subsetWeights[i] = weights[modelIDs[i] - 1];
    }
    return subsetWeights;
  }

  static abstract class ModelGroup extends NgaEast_2016 {

    final int[] models;
    final double[] weights;

    ModelGroup(Imt imt, int[] models) {
      super(imt);
      this.models = models;
      this.weights = Data.normalize(selectWeights(super.weights, models));
    }

    @Override
    public MultiScalarGroundMotion calc(GmmInput in) {
      Position p = super.tables[0].position(in.rRup, in.Mw);
      double[] μs = new double[models.length];
      for (int i = 0; i < models.length; i++) {
        μs[i] = super.tables[models[i] - 1].get(p);
      }
      double[] σs = calcSigmas(in.Mw);
      double[] σWts = σs.length > 1 ? SIGMA_WTS : new double[] { 1.0 };
      return new MultiScalarGroundMotion(μs, weights, σs, σWts);
    }

    /* provided for overrides */
    double[] calcSigmas(double Mw) {
      return super.calcSigmasTotal(Mw);
    }
  }

  static class Center extends ModelGroup {
    static final String NAME = NgaEast_2016.NAME + ": Center";

    Center(Imt imt) {
      super(imt, R0);
    }
  }

  static final class Group1 extends ModelGroup {
    static final String NAME = NgaEast_2016.NAME + ": Group1";

    Group1(Imt imt) {
      super(imt, Ints.concat(R0, R1));
    }
  }

  static final class Group2 extends ModelGroup {
    static final String NAME = NgaEast_2016.NAME + ": Group2";

    Group2(Imt imt) {
      super(imt, Ints.concat(R0, R1, R2));
    }
  }

  static class Total extends ModelGroup {
    static final String NAME = NgaEast_2016.NAME + ": Total (Total sigma)";

    Total(Imt imt) {
      super(imt, Ints.concat(R0, R1, R2, R3));
    }
  }

  static final class TotalSigmaCenter extends Total {
    static final String NAME = NgaEast_2016.NAME + ": Total (Central sigma)";

    TotalSigmaCenter(Imt imt) {
      super(imt);
    }

    @Override
    double[] calcSigmas(double Mw) {
      return super.calcSigmasMid(Mw);
    }
  }

  static final class TotalSigmaNgaw2 extends Total {
    static final String NAME = NgaEast_2016.NAME + ": Total (NGAW2 sigma)";

    TotalSigmaNgaw2(Imt imt) {
      super(imt);
    }

    @Override
    double[] calcSigmas(double Mw) {
      return super.calcSigmasNgaw2(Mw);
    }
  }

  // TODO clean
  public static void main(String[] args) {
    Total ngaEast1 = new Total(Imt.SA0P2);
    Total ngaEast2 = new TotalSigmaCenter(Imt.SA0P2);
    Total ngaEast3 = new TotalSigmaNgaw2(Imt.SA0P2);

    GmmInput.Builder builder = GmmInput.builder().withDefaults();
    builder.rRup(10);
    GmmInput in = builder.build();

    System.out.println(in);
    System.out.println(ngaEast1.calc(in));
    System.out.println(ngaEast2.calc(in));
    System.out.println(ngaEast3.calc(in));

    // System.out.println(Arrays.toString(ngaEast.models));
    // System.out.println(Arrays.toString(ngaEast.weights));
    // System.out.println(Arrays.toString(ngaEast.R0_weights));
  }

}
