package gui.radial.symmetry.plugin;

import java.awt.Font;
import java.util.ArrayList;
import java.util.Arrays;

import compute.RadialSymmetry;
import compute.RadialSymmetry.Ransac;
import fiji.util.gui.GenericDialogPlus;
import fitting.Spot;
import gui.interactive.HelperFunctions;
import gui.interactive.InteractiveRadialSymmetry;
import gui.vizualization.Visualization;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import imglib2.RealTypeNormalization;
import imglib2.TypeTransformingRandomAccessibleInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import parameters.RadialSymParams;
import result.output.ShowResult;

public class Radial_Symmetry implements PlugIn
{
	@Override
	public void run(String arg) {

		// get list of open image stacks
		final int[] idList = WindowManager.getIDList();

		if ( idList == null || idList.length == 0 )
		{
			IJ.error( "You need at least one open image." );
			return;
		}

		// map all id's to image title for those who are 3d stacks
		final String[] imgList =
				Arrays.stream( idList ).
						mapToObj( id -> WindowManager.getImage( id ).getTitle() ).
							toArray( String[]::new );

		if ( RadialSymParams.defaultImg >= imgList.length )
			RadialSymParams.defaultImg = 0;

		GenericDialog gd1 = new GenericDialog( "Radial Symmetry (2d/3d)" );

		gd1.addChoice( "Image", imgList, imgList[ RadialSymParams.defaultImg ] );
		gd1.addChoice( "Mode", RadialSymParams.modeChoice, RadialSymParams.modeChoice[ RadialSymParams.defaultMode ] );
		gd1.addNumericField( "Anisotropy coefficient", RadialSymParams.defaultAnisotropy, 4, 15, "s(z)/s(xy)" );
		gd1.addCheckbox( "Use_anisotropy coefficient for DoG", RadialSymParams.defaultUseAnisotropyForDoG );

		gd1.addMessage( "<html>*Use the \"Anisotropy Coefficient Plugin\"<br/>to calculate the anisotropy coefficient<br/> or leave 1.00 for a hopefully reasonable result.", new Font( "Default", Font.ITALIC, 10 ) );

		gd1.addMessage( "Computation:", new Font( "Default", Font.BOLD, 13 ) );
		gd1.addChoice( "Robust_fitting", RadialSymParams.ransacChoice, RadialSymParams.ransacChoice[ RadialSymParams.defaultRANSACChoice ] );

		gd1.addMessage( "Visualization:", new Font( "Default", Font.BOLD, 13 ) );
		gd1.addCheckbox( "Visually select spots by intensity using a histogram (Interactive Mode only)", RadialSymParams.defaultVisualizeDetections );
		//gd1.addCheckbox( "Visualize_Inliers (RANSAC)", RadialSymParams.defaultVisualizeInliers );

		gd1.showDialog();
		if ( gd1.wasCanceled() )
			return;

		// set the parameters from the defaults
		final RadialSymParams params = new RadialSymParams();

		// don't do it by name as often multiple images have the same name
		ImagePlus imp = WindowManager.getImage( idList[ RadialSymParams.defaultImg = gd1.getNextChoiceIndex() ] );
		int mode = RadialSymParams.defaultMode = gd1.getNextChoiceIndex();
		params.anisotropyCoefficient = RadialSymParams.defaultAnisotropy = gd1.getNextNumber();
		params.useAnisotropyForDoG = RadialSymParams.defaultUseAnisotropyForDoG = gd1.getNextBoolean();
		params.RANSAC = Ransac.values()[ RadialSymParams.defaultRANSACChoice = gd1.getNextChoiceIndex() ];

		boolean visDetections = RadialSymParams.defaultVisualizeDetections = gd1.getNextBoolean();
		//boolean visInliers = RadialSymParams.defaultVisualizeInliers = gd1.getNextBoolean();

		if (imp.getNChannels() > 1)
		{
			IJ.log( "Multichannel image detected. Please split by channel and select parameters for each channel separately.");
			return;
		}

		if ( params.RANSAC.ordinal() == 2 ) // Multiconsensus RANSAC
		{
			GenericDialogPlus gd2 = new GenericDialogPlus( "Multiconsensus RANSAC Options" );
			gd2.addNumericField( "Min_number_of_inliers", RadialSymParams.defaultMinNumInliers, 0 );
			gd2.addNumericField( "Initial #inlier threshold for new spot (avg - n*stdev) n=", RadialSymParams.defaultNTimesStDev1, 2 );
			gd2.addNumericField( "Final #inlier threshold for new spot (avg - n*stdev) n=", RadialSymParams.defaultNTimesStDev2, 2 );

			gd2.showDialog();
			if ( gd2.wasCanceled() )
				return;

			params.minNumInliers = RadialSymParams.defaultMinNumInliers = (int)Math.round( gd2.getNextNumber() );
			params.nTimesStDev1 = RadialSymParams.defaultNTimesStDev1 = gd2.getNextNumber();
			params.nTimesStDev2 = RadialSymParams.defaultNTimesStDev2 = gd2.getNextNumber();
		}
		else
		{
			params.minNumInliers = 0;
		}

		// dirty cast that can't be avoided :(
		double[] minmax = HelperFunctions.computeMinMax((Img) ImageJFunctions.wrapReal(imp));

		float min = (float) minmax[0];
		float max = (float) minmax[1];

		IJ.log( "img min=" + min + ", max=" + max );

		// TODO: REMOVE
		//mode = 1;
		//RadialSymParams.defaultSigma = 2.0f;
		//RadialSymParams.defaultThreshold =  0.02f;
		//params.setAnisotropyCoefficient( 0.675 );

		if ( mode == 1) {// advanced

			GenericDialogPlus gd2 = new GenericDialogPlus( "Advanced Options" );

			gd2.addNumericField( "Sigma (DoG)", RadialSymParams.defaultSigma, 5, 15, "" );
			gd2.addNumericField( "Threshold (DoG)", RadialSymParams.defaultThreshold, 5, 15, "" );
			gd2.addNumericField( "Support region radius (RANSAC)", RadialSymParams.defaultSupportRadius, 0 );
			gd2.addNumericField( "Min_inlier_ratio (RANSAC)", RadialSymParams.defaultInlierRatio, 2 );
			gd2.addNumericField( "Max_error (RANSAC)", RadialSymParams.defaultMaxError, 2 );
			gd2.addNumericField( "Spot_intensity_threshold", RadialSymParams.defaultIntensityThreshold, 2 );

			gd2.addMessage( "" );
			gd2.addChoice( "Background subtraction", RadialSymParams.bsMethods, RadialSymParams.bsMethods[ RadialSymParams.defaultBsMethodChoice ]);
			gd2.addNumericField( "Background_subtraction_max_error", RadialSymParams.defaultBsMaxError, 2 );
			gd2.addNumericField( "Background_subtraction_min_inlier_ratio", RadialSymParams.defaultBsInlierRatio, 2 );

			gd2.addMessage( "" );
			gd2.addFileField( "Results_file", RadialSymParams.defaultResultsFilePath );

			gd2.showDialog();
			if ( gd2.wasCanceled() )
				return;

			params.sigma = RadialSymParams.defaultSigma = (float)gd2.getNextNumber();
			params.threshold = RadialSymParams.defaultThreshold = (float)gd2.getNextNumber();
			params.supportRadius = RadialSymParams.defaultSupportRadius = Math.round( (float)gd2.getNextNumber() );
			params.inlierRatio = RadialSymParams.defaultInlierRatio = (float)gd2.getNextNumber();
			params.maxError = RadialSymParams.defaultMaxError = (float)gd2.getNextNumber();
			params.intensityThreshold = RadialSymParams.defaultIntensityThreshold = gd2.getNextNumber();
			params.bsMethod = RadialSymParams.defaultBsMethodChoice = gd2.getNextChoiceIndex();
			params.bsMaxError = RadialSymParams.defaultBsMaxError = (float)gd2.getNextNumber();
			params.bsInlierRatio = RadialSymParams.defaultBsInlierRatio = (float)gd2.getNextNumber();
			params.resultsFilePath = RadialSymParams.defaultResultsFilePath = gd2.getNextString().trim();
		}
		else // interactive
		{
			InteractiveRadialSymmetry irs = new InteractiveRadialSymmetry(imp, params, min, max);
			do {
				// TODO: change to something that is not deprecated
				SimpleMultiThreading.threadWait(100);
			} while (!irs.isFinished());

			if (irs.wasCanceled())
				return;

			// update defaults with selections from the interactive GUI
			params.setDefaultValuesFromInteractive();
		}

		// normalize the whole image if it is possible
		RandomAccessibleInterval<FloatType> rai;
		if (!Double.isNaN(min) && !Double.isNaN(max)) // if normalizable
			rai = new TypeTransformingRandomAccessibleInterval<>(ImageJFunctions.wrap(imp),
					new RealTypeNormalization<>(min, max - min), new FloatType());
		else // otherwise use
			rai = ImageJFunctions.wrap(imp);

		int[] impDim = imp.getDimensions(); // x y c z t

		ArrayList<Spot> allSpots = new ArrayList<>(0);
		// stores number of detected spots per time point
		ArrayList<Long> timePoint = new ArrayList<>(0);
		// stores number of detected spots per channel
		ArrayList<Long> channelPoint = new ArrayList<>(0);

		// Ensure that the input is a FloatType
		RandomAccessibleInterval<RealType> wrapped = ImageJFunctions.wrapReal(imp);
		RandomAccessibleInterval<FloatType> input = Converters.convert(
				wrapped,
				(a, b) -> b.setReal(a.getRealFloat()),
				new FloatType());

		RadialSymmetry.processSliceBySlice(input, rai, params, impDim, allSpots, timePoint, channelPoint);

		if ( mode == 0 ) { // interactive
			// TODO: keep here?
			imp.deleteRoi();

			// shows the histogram and sets the intensity threshold
			params.intensityThreshold = RadialSymParams.defaultIntensityThreshold = 
					Visualization.visuallyDefineThreshold(
							imp, allSpots, timePoint,
							params.getSigmaDoG(), params.getAnisotropyCoefficient());

			ShowResult.ransacResultTable(allSpots, timePoint, channelPoint, params.intensityThreshold );
		}
		else if ( mode == 1 ) { // advanced
			// write the result to the csv file
			IJ.log( "Intensity threshold =" + params.intensityThreshold );
			ResultsTable rt = ShowResult.ransacResultTable(allSpots, timePoint, channelPoint, params.intensityThreshold );

			if( params.resultsFilePath.length() > 0 )
			{
				System.out.println("Writing to results path: " + params.resultsFilePath);
				rt.save(params.resultsFilePath);
			}
		} else
			System.out.println("Wrong parameters' mode");

	}

	public static void main(String[] args) {
		//net.imagej.ImageJ ij = new net.imagej.ImageJ();
		//ij.launch( "/Users/spreibi/Downloads/N2_267-1.tif" );
		//ij.launch( "/Users/spreibi/Downloads/C0-N2_352_cropped_1240.tif" );
		//ij.launch( "/home/kharrington/Data/Radial_Symmetry/N2_352-1.tif" );

		//ij.launch( "/Users/spreibi/Documents/BIMSB/Publications/radialsymmetry/Poiss_30spots_bg_200_0_I_10000_0_img0.tif");
		//ij.launch( "/Users/spreibi/Documents/BIMSB/Publications/radialsymmetry/Poiss_30spots_bg_200_1_I_300_0_img0.tif");
		//ij.launch( "/Users/spreibi/Documents/BIMSB/Publications/radialsymmetry/Poiss_300spots_bg_200_0_I_10000_0_img0.tif" );

		//ij.command().run(Radial_Symmetry.class, true);

		new ImageJ();
		new ImagePlus("/Users/spreibi/Documents/BIMSB/Publications/radialsymmetry/Poiss_30spots_bg_200_1_I_300_0_img0.tif" ).show();
		//new ImagePlus( "/Users/spreibi/Documents/BIMSB/Publications/radialsymmetry/Poiss_30spots_bg_200_0_I_10000_0_img0.tif" ).show();
		new Radial_Symmetry().run( null );

	}
}
