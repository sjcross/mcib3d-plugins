package mcib_plugins;

import java.awt.Checkbox;
import java.awt.Font;
import java.awt.TextField;
import java.util.Date;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.frame.Recorder;
import ij.process.ImageProcessor;
import mcib3d.image3d.ImageShort;
import mcib3d.image3d.processing.FastFilters3D;
import mcib3d.utils.CheckInstall;
import mcib3d.utils.ThreadUtil;
import mcib_plugins.Filter3D.Filter3Dmax;
import mcib_plugins.Filter3D.Filter3DmaxLocal;
import mcib_plugins.Filter3D.Filter3Dmean;
import mcib_plugins.Filter3D.Filter3Dmin;

/**
 * 3D filtering
 *
 * @author Thomas BOUDIER
 * @created feb 2008
 */
@SuppressWarnings("empty-statement")
public class Fast_filters3D implements PlugInFilter, DialogListener {

    private int nbcpus;
    private int nRows = 2;
    private int nCols = 2;
    ImagePlus imp;
    private String[] filters = new String[] { "Mean", "Median", "Minimum", "Maximum", "MaximumLocal", "TopHat",
            "OpenGray", "CloseGray", "Variance", "Sobel", "Adaptive" };
    private String[] algos = new String[] { "Parallelized", "Isotropic" };
    private int filter;
    private float voisx = 2;
    private float voisy = 2;
    private float voisz = 2;
    private boolean xy = true;
    private Calibration calibration;
    private double uvoisx = 0;
    private double uvoisy = 0;
    private double uvoisz = 0;
    private boolean debug = false;
    private int algo = 0;

    /**
     * Main processing method for the Median3D_ object
     *
     * @param ip Image
     */
    @Override
    public void run(ImageProcessor ip) {
        if (!CheckInstall.installComplete()) {
            IJ.log("Not starting Filters 3D");
            return;
        }

        calibration = imp.getCalibration();
        ImagePlus extract = extractCurrentStack(imp);
        ImageStack stack = extract.getStack();
        int depth = stack.getBitDepth();

        if (Dialogue()) {
            // Macro
            if (Recorder.record) {
                Recorder.setCommand(null);
                Recorder.record("run", "3D Fast Filters\",\"filter=" + filters[filter] + " radius_x_pix=" + voisx
                        + " radius_y_pix=" + voisy + " radius_z_pix=" + voisz + " Nb_cpus=" + nbcpus);
                if (debug) {
                    IJ.log("Performing 3D filter " + filters[filter] + " " + voisx + "x" + voisy + "x" + voisz);
                }
            }

            boolean ff = ((voisx == voisy) && (voisx == voisz)
                    && ((filter == FastFilters3D.MEAN) || (filter == FastFilters3D.MIN) || (filter == FastFilters3D.MAX)
                            || (filter == FastFilters3D.MAXLOCAL) || (filter == FastFilters3D.TOPHAT)
                            || (filter == FastFilters3D.OPENGRAY) || (filter == FastFilters3D.CLOSEGRAY)));

            Date t0 = new Date();

            if ((ff) && (algo == 1)) {
                if (debug) {
                    IJ.log("Using isotropic filtering");
                }
                FastFilter(extract, (int) voisx, filters[filter]);
            } else {
                ImageStack res = null;
                if ((depth == 8) || (depth == 16)) {
                    res = FastFilters3D.filterIntImageStack(stack, filter, voisx, voisy, voisz, nbcpus, true);
                } else if (imp.getBitDepth() == 32) {
                    // if ((filter != FastFilters3D.SOBEL)) {
                    res = FastFilters3D.filterFloatImageStack(stack, filter, voisx, voisy, voisz, nbcpus, true);
                    // } else {
                    // if (debug) {
                    // IJ.log("Not implemented for 32-bits images");
                    // }
                    // }
                    // }
                } else {
                    IJ.log("Does not work with stack with bitDepth " + depth);
                }
                if (res != null) {
                    ImagePlus plus = new ImagePlus("3D_" + filters[filter], res);
                    plus.setCalibration(calibration);
                    plus.show();
                }

            }
            // time to process
            Date t1 = new Date();
            if (debug) {
                IJ.log("time : " + (t1.getTime() - t0.getTime()) + " ms");
            }
        }
    }

    private void FastFilter(ImagePlus in_image_j, int radius, String selected_filter) {
        // read image
        // ImagePlus in_image_j = IJ.getImage();
        ImageStack inStack = in_image_j.getStack();
        ImageStack outStack = new ImageShort("out3d", inStack.getWidth(), inStack.getHeight(), inStack.getSize())
                .getImageStack();
        int rad = radius;

        ThreadPoolExecutor pool = new ThreadPoolExecutor(nbcpus, nbcpus, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());

        for (int row = 0; row < nRows; row++) {
            for (int col = 0; col < nCols; col++) {
                final int rowF = row;
                final int colF = col;

                Runnable task = () -> {
                    ImageStack cropStack = getPutBlock(inStack, null, nRows, nCols, rowF, colF, rad);
                    ImageStack tempOutStack = new ImageShort("outImage", cropStack.getWidth(), cropStack.getHeight(),
                            cropStack.size()).getImageStack();
                    processFilter(cropStack, tempOutStack, selected_filter, rad);
                    getPutBlock(tempOutStack, outStack, nRows, nCols, rowF, colF, rad);
                };
                pool.submit(task);
            }
        }

        pool.shutdown();
        try {
            pool.awaitTermination(Integer.MAX_VALUE, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        ImagePlus out_plus = new ImagePlus(filters[filter], outStack);
        out_plus.setCalibration(calibration);
        out_plus.show();

    }

    synchronized public ImageStack getPutBlock(ImageStack instack, ImageStack outStack, int nRows, int nCols, int row,
            int col, int rad) {
        if (outStack == null) {
            return getBlock(instack, nRows, nCols, row, col, rad);
        } else {
            putBlock(instack, outStack, nRows, nCols, row, col, rad);
            return null;
        }
    }

    synchronized public ImageStack getBlock(ImageStack instack, int nRows, int nCols, int row, int col, int rad) {
        int width = instack.getWidth();
        int height = instack.getHeight();
        int nSlices = instack.size();

        int blockWidth = (int) Math.round(width / nCols);
        int blockHeight = (int) Math.round(height / nRows);

        int borderN = row == 0 ? 0 : rad;
        int borderS = row == (nRows - 1) ? 0 : rad;
        int borderW = col == 0 ? 0 : rad;
        int borderE = col == (nCols - 1) ? 0 : rad;

        int x = (col * blockWidth) - borderW;
        int y = (row * blockHeight) - borderN;
        int w = blockWidth + borderW + borderE;
        int h = blockHeight + borderN + borderS;

        // Padding to edge for final row or column
        if (row == nRows - 1)
            h = height - y;
        if (col == nCols - 1)
            w = width - x;

        return instack.crop(x, y, 0, w, h, nSlices);

    }

    synchronized public void putBlock(ImageStack instack, ImageStack out_image, int nRows, int nCols, int row, int col,
            int rad) {
        int width = out_image.getWidth();
        int height = out_image.getHeight();
        int nSlices = out_image.size();

        int normBlockWidth = (int) Math.round(width / nCols);
        int normBlockHeight = (int) Math.round(height / nRows);
        int blockWidth = normBlockWidth;
        int blockHeight = normBlockHeight;

        int borderN = row == 0 ? 0 : rad;
        int borderW = col == 0 ? 0 : rad;

        if (row == nRows - 1) 
            blockHeight = blockHeight + (instack.getHeight() - blockHeight - borderN);
        
        if (col == nCols - 1)
            blockWidth = blockWidth + (instack.getWidth() - blockWidth - borderW);

        for (int x = 0; x < blockWidth; x++) {
            for (int y = 0; y < blockHeight; y++) {
                for (int z = 0; z < nSlices; z++) {
                    double val = instack.getVoxel(x + borderW, y + borderN, z);
                    out_image.setVoxel(col * normBlockWidth + x, row * normBlockHeight + y, z, val);
                }
            }
        }
    }

    public void processFilter(ImageStack inStack, ImageStack outStack, String selected_filter, int rad) {
        switch (selected_filter) {
            case "Mean":
                Filter3Dmean mean = new Filter3Dmean(inStack, outStack, rad);
                mean.filter();
                break;
            case "Minimum": {
                Filter3Dmin min = new Filter3Dmin(inStack, outStack, rad);
                min.filter();
                break;
            }
            case "Maximum": {
                Filter3Dmax max = new Filter3Dmax(inStack, outStack, rad);
                max.filter();

                break;
            }
            case "MaximumLocal": {
                Filter3DmaxLocal max = new Filter3DmaxLocal(inStack, outStack, rad);
                max.filter();
                break;
            }
        }
    }

    @Override
    public boolean dialogItemChanged(GenericDialog gd, java.awt.AWTEvent e) {
        Vector fields = gd.getNumericFields();
        Vector fieldsb = gd.getCheckboxes();
        xy = ((Checkbox) fieldsb.elementAt(0)).getState();
        // System.out.println("" + voisx + " " + voisy + " " + voisz);
        // NumberFormat nf = NumberFormat.getInstance(Locale.ENGLISH);
        // nf.setMaximumFractionDigits(3);

        try {
            if ((e != null) && (!gd.invalidNumber())) {
                switch (fields.indexOf(e.getSource())) {
                    //////// X
                    case 0:
                        double v0 = Double.valueOf(((TextField) fields.elementAt(0)).getText());
                        if (v0 != uvoisx) {
                            ((TextField) fields.elementAt(1))
                                    .setText(Integer.toString((int) Math.round(v0 / calibration.pixelWidth)));
                            uvoisx = v0;
                            voisx = (int) Math.round(v0 / calibration.pixelWidth);
                            if (xy) {
                                uvoisy = uvoisx;
                                voisy = voisx;
                                ((TextField) fields.elementAt(2)).setText("" + uvoisy);
                                ((TextField) fields.elementAt(3)).setText(Integer.toString(Math.round(voisy)));
                            }
                        }
                        break;

                    case 1:
                        int v1 = Integer.valueOf(((TextField) fields.elementAt(1)).getText());
                        if (v1 != voisx) {
                            ((TextField) fields.elementAt(0)).setText("" + v1 * calibration.pixelWidth);
                            voisx = v1;
                            uvoisx = v1 * calibration.pixelWidth;
                            if (xy) {
                                uvoisy = uvoisx;
                                voisy = voisx;
                                ((TextField) fields.elementAt(2)).setText("" + uvoisy);
                                ((TextField) fields.elementAt(3)).setText(Integer.toString(Math.round(voisy)));
                            }
                        }
                        break;
                    //////// Y
                    case 2:
                        double v3 = Double.valueOf(((TextField) fields.elementAt(2)).getText());
                        if (v3 != uvoisy) {
                            ((TextField) fields.elementAt(3))
                                    .setText(Integer.toString((int) Math.round(v3 / calibration.pixelHeight)));
                            uvoisy = v3;
                            voisy = (int) Math.round(v3 / calibration.pixelHeight);
                            if (xy) {
                                uvoisx = uvoisy;
                                voisx = voisy;
                                ((TextField) fields.elementAt(0)).setText("" + uvoisx);
                                ((TextField) fields.elementAt(1)).setText(Integer.toString(Math.round(voisx)));
                            }
                        }
                        break;

                    case 3:
                        int v2 = Integer.valueOf(((TextField) fields.elementAt(3)).getText());
                        if (v2 != voisy) {
                            ((TextField) fields.elementAt(2)).setText("" + v2 * calibration.pixelHeight);
                            voisy = v2;
                            uvoisy = v2 * calibration.pixelHeight;
                            if (xy) {
                                uvoisx = uvoisy;
                                voisx = voisy;
                                ((TextField) fields.elementAt(0)).setText("" + uvoisx);
                                ((TextField) fields.elementAt(1)).setText(Integer.toString(Math.round(voisx)));
                            }
                        }
                        break;
                    //////// Z
                    case 4:
                        double v4 = Double.valueOf(((TextField) fields.elementAt(4)).getText());
                        if (v4 != uvoisz) {
                            ((TextField) fields.elementAt(5))
                                    .setText(Integer.toString((int) Math.round(v4 / calibration.pixelDepth)));
                            uvoisz = v4;
                            voisz = (int) Math.round(v4 / calibration.pixelDepth);
                        }
                        break;
                    case 5:
                        int v5 = Integer.valueOf(((TextField) fields.elementAt(5)).getText());
                        if (v5 != voisz) {
                            ((TextField) fields.elementAt(4)).setText("" + v5 * calibration.pixelDepth);
                            voisz = v5;
                            uvoisz = v5 * calibration.pixelDepth;
                        }
                        break;
                    default:
                        break;
                }
            }
            // if (!gd.invalidNumber()) ;
        } catch (NumberFormatException nfe) {
            IJ.log(nfe.getMessage());
        }
        return true;
    }

    /**
     * Dialogue of the plugin
     *
     * @return ok or cancel
     */
    private boolean Dialogue() {
        String unit = calibration.getUnits();
        GenericDialog gd = new GenericDialog("3D_Filter");
        gd.addChoice("Filter", filters, filters[0]);
        gd.addMessage("Kernel_X", new Font("Arial", Font.BOLD, 12));
        gd.addNumericField("Radius_X_unit", voisx * calibration.pixelWidth, 0, 8, unit);
        gd.addNumericField("Radius_X_pix", voisx, 0, 8, "pix");
        gd.addMessage("Kernel_Y", new Font("Arial", Font.BOLD, 12));
        gd.addNumericField("Radius_Y_unit", voisy * calibration.pixelHeight, 0, 8, unit);
        gd.addNumericField("Radius_Y_pix", voisy, 0, 8, "pix");
        gd.addCheckbox("Synchronize X-Y", xy);
        gd.addMessage("kernel_Z", new Font("Arial", Font.BOLD, 12));
        gd.addNumericField("Radius_Z_unit", voisz * calibration.pixelDepth, 0, 8, unit);
        gd.addNumericField("Radius_Z_pix", voisz, 0, 8, "pix");
        gd.addMessage("Parallelization", new Font("Arial", Font.BOLD, 12));
        gd.addChoice("Algorithm", algos, algos[algo]);
        gd.addSlider("Nb_cpus", 1, ThreadUtil.getNbCpus(), ThreadUtil.getNbCpus());
        gd.addNumericField("Nb_rows", nRows, 0);
        gd.addNumericField("Nb_cols", nCols, 0);
        if (!IJ.macroRunning()) {
            gd.addDialogListener(this);
        }
        gd.showDialog();
        filter = gd.getNextChoiceIndex();
        uvoisx = gd.getNextNumber();
        voisx = (int) gd.getNextNumber();
        uvoisy = gd.getNextNumber();
        voisy = (int) gd.getNextNumber();
        xy = gd.getNextBoolean();
        uvoisz = gd.getNextNumber();
        voisz = (int) gd.getNextNumber();
        algo = gd.getNextChoiceIndex();
        nbcpus = (int) gd.getNextNumber();
        nRows = (int) gd.getNextNumber();
        nCols = (int) gd.getNextNumber();

        return (!gd.wasCanceled());
    }

    /**
     * setup
     *
     * @param arg Argument of setup
     * @param imp ImagePlus info
     * @return ok
     */
    @Override
    public int setup(String arg, ImagePlus imp) {
        this.imp = imp;

        return DOES_8G + DOES_16 + DOES_32;
    }

    public void setRadiusXYPix(int rad) {
        voisx = voisy = rad;
    }

    public void setRadiusZPix(int rad) {
        voisz = rad;
    }

    public void setFilter(int f) {
        filter = f;
    }

    public void setNbCpus(int nb) {
        if (nb > 0) {
            nbcpus = nb;
        } else {
            nbcpus = ThreadUtil.getNbCpus();
        }
    }

    private ImagePlus extractCurrentStack(ImagePlus plus) {
        // check dimensions
        int[] dims = plus.getDimensions();// XYCZT
        int channel = plus.getChannel();
        int frame = plus.getFrame();
        ImagePlus stack;
        // crop actual frame
        if ((dims[2] > 1) || (dims[4] > 1)) {
            IJ.log("Hyperstack found, extracting current channel " + channel + " and frame " + frame);
            Duplicator duplicator = new Duplicator();
            stack = duplicator.run(plus, channel, channel, 1, dims[3], frame, frame);
        } else
            stack = plus.duplicate();

        return stack;
    }
}
