/*
 * Copyright (C) 2018 by Airbus UK (ENS Portsmouth), Brockmann Consult GmbH
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

// Modified from Radarsat 2 version in Feb 2018 by Airbus UK (ENS Portsmouth) for NovaSAR compatibility

package com.airbus.snap.dataio.novasar;

import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageReader;
import org.apache.commons.math3.util.FastMath;
import org.esa.s1tbx.commons.io.SARReader;
import org.esa.s1tbx.commons.io.XMLProductDirectory;
import org.esa.s1tbx.commons.io.ImageIOFile;

//import org.esa.snap.core.datamodel.*; // Can this line be used to replace the next 8 lines??
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGeoCoding;
import org.esa.snap.core.datamodel.TiePointGrid;

import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

//========================================================================================================================================================================================
// Class to represent a NovaSAR Product Directory
//========================================================================================================================================================================================
public class NovaSARProductDirectory extends XMLProductDirectory
{
    private String productName = "NovaSAR";
    private String productType = "NovaSAR";
    private final String productDescription = "";
    private boolean compactPolMode = false;
    final String defStr = AbstractMetadata.NO_METADATA_STRING;
    final int defInt = AbstractMetadata.NO_METADATA;

    private static final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");

    private static final boolean flipToSARGeometry = System.getProperty(SystemUtils.getApplicationContextId() + ".flip.to.sar.geometry", "false").equals("true");

    private final transient Map<String, String> polarizationMap = new HashMap<>(4);

//========================================================================================================================================================================================
// Function to
//========================================================================================================================================================================================
NovaSARProductDirectory(final File headerFile)
    {
        super(headerFile);
    }

//========================================================================================================================================================================================
// Function to
//========================================================================================================================================================================================
protected String getHeaderFileName()
    {
        return NovaSARConstants.PRODUCT_HEADER_NAME;
    }

//========================================================================================================================================================================================
// Function to
//========================================================================================================================================================================================
protected void addImageFile(final String imgPath, final MetadataElement newRoot) throws IOException
    {
        final String name = getBandFileNameFromImage(imgPath);
        if ((name.endsWith("tif") || name.endsWith("tiff")))
        {
            boolean valid = false;
            int dataType = ProductData.TYPE_INT32;
            if (name.startsWith("image")) {
                valid = true;
            } else if (name.startsWith("rh") || name.startsWith("rv"))
            {
                valid = true;
                dataType = ProductData.TYPE_FLOAT32;
            }
            if (valid) {
                final Dimension bandDimensions = getBandDimensions(newRoot, name);
                final InputStream inStream = getInputStream(imgPath);
                final ImageInputStream imgStream = ImageIOFile.createImageInputStream(inStream, bandDimensions);
                if (imgStream == null)
                    throw new IOException("Unable to open " + imgPath);

                final ImageIOFile img;
                if (isSLC()) {
                    img = new ImageIOFile(name, imgStream, getTiffIIOReader(imgStream),
                                          1, 2, dataType, productInputFile);
                } else {
                    img = new ImageIOFile(name, imgStream, getTiffIIOReader(imgStream), productInputFile);
                }
                bandImageFileMap.put(img.getName(), img);
            }
        }
    } // End of addImageFile()


    private static ImageReader getTiffIIOReader(final ImageInputStream stream) throws IOException {
        ImageReader reader = null;
        final Iterator<ImageReader> imageReaders = ImageIO.getImageReaders(stream);
        while (imageReaders.hasNext()) {
            final ImageReader iioReader = imageReaders.next();
            if (iioReader instanceof TIFFImageReader) {
                reader = iioReader;
                break;
            }
        }
        if (reader == null)
            throw new IOException("Unable to open " + stream.toString());
        reader.setInput(stream, true, true);
        return reader;
    }

    //========================================================================================================================================================================================
// Function to 
//========================================================================================================================================================================================
private String getPol(final String imgName)
    {
        String pol = polarizationMap.get(imgName);
        if (pol == null)
        {
            if (imgName.contains("rh"))
            {
                compactPolMode = true;
                return "RH";
            } else if (imgName.contains("rv"))
            {
                compactPolMode = true;
                return "RV";
            }
        }
        return pol;
    } // End of getPol()

//========================================================================================================================================================================================
// Function to 
//========================================================================================================================================================================================
@Override
protected void addBands(final Product product)
    {

        String bandName;
        boolean real = true;
        Band lastRealBand = null;
        String unit;

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final int width = absRoot.getAttributeInt(AbstractMetadata.num_samples_per_line);
        final int height = absRoot.getAttributeInt(AbstractMetadata.num_output_lines);

        final Set<String> keys = bandImageFileMap.keySet();   // The set of keys in the map.
        for (String key : keys)
        {
            final ImageIOFile img = bandImageFileMap.get(key);

            for (int i = 0; i < img.getNumImages(); ++i)
            {

                if (isSLC())
                {
                    for (int b = 0; b < img.getNumBands(); ++b)
                    {
                        final String imgName = img.getName().toLowerCase();
                        if (real) {
                            bandName = "i_" + getPol(imgName);
                            unit = Unit.REAL;
                        }
                        else
                        {
                            bandName = "q_" + getPol(imgName);
                            unit = Unit.IMAGINARY;
                        }

                        final Band band = new Band(bandName, img.getDataType(), width, height);
                        band.setUnit(unit);

                        product.addBand(band);
                        bandMap.put(band, new ImageIOFile.BandInfo(band, img, i, b));

                        if (real)
                        {
                            lastRealBand = band;
                        }
                        else
                        {
                            ReaderUtils.createVirtualIntensityBand(product, lastRealBand, band, '_' + getPol(imgName));
                        }
                        real = !real;
                    }
                }
                else
                {
                    for (int b = 0; b < img.getNumBands(); ++b)
                    {
                        final String imgName = img.getName().toLowerCase();
                        bandName = "Amplitude_" + getPol(imgName);
                        final Band band = new Band(bandName, ProductData.TYPE_UINT32, width, height);
                        band.setUnit(Unit.AMPLITUDE);

                        product.addBand(band);
                        bandMap.put(band, new ImageIOFile.BandInfo(band, img, i, b));

                        SARReader.createVirtualIntensityBand(product, band, '_' + getPol(imgName));
                    }
                }
            }
        }

        if (compactPolMode)
        {
            absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
            absRoot.setAttributeString(AbstractMetadata.compact_mode, "Right Circular Hybrid Mode");
        }
    } // End of addBands()

//========================================================================================================================================================================================
// Function to 
//========================================================================================================================================================================================
@Override
protected void addAbstractedMetadataHeader(final MetadataElement root) throws IOException
   {

    final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
    final MetadataElement origProdRoot = AbstractMetadata.addOriginalProductMetadata(root);

    //=====================================================================
    // LOAD IN THE METADATA FILE AND EXTRACT FIRST SUB-LEVEL SUB-STRUCTURES
    //=====================================================================

    // Load entire metadata file into 'productElem' structure ('Level 0' in metadata file hierarchy)
    final MetadataElement productElem = origProdRoot.getElement("metadata");

    // Extract Product Attributes ('Level 1' in metadata file hierarchy)
    final MetadataElement productAttributes = productElem.getElement("Product");

    // Extract Source Attributes ('Level 1' in metadata file hierarchy)
    final MetadataElement sourceAttributes = productElem.getElement("Source_Attributes");

    // Extract Orbit Data ('Level 1' in metadata file hierarchy)
    final MetadataElement orbitData = productElem.getElement("OrbitData");

    // Extract Image Generation Parameters ('Level 1' in metadata file hierarchy)
    final MetadataElement imageGenerationParameters = productElem.getElement("Image_Generation_Parameters");

    // Extract Image Attributes ('Level 1' in metadata file hierarchy)
    final MetadataElement imageAttributes = productElem.getElement("Image_Attributes");

    // Extract Geographic Information ('Level 1' in metadata file hierarchy)
    final MetadataElement geographicInformation = productElem.getElement("geographicInformation");

    // Extract Full Resolution Image Data ('Level 1' in metadata file hierarchy)
    final MetadataElement fullResolutionImageData = productElem.getElement("fullResolutionImageData");


    //================================================================================================================
    // EXTRACT PARAMETERS FROM LOADED METADATA AND COPY THEM INTO THE APPROPRIATE ENTRIES IN THE ABSTRACTED METADATA
    //================================================================================================================

    // Extract Antenna Pointing (Left/Right)
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing, sourceAttributes.getAttributeString("AntennaPointing", defStr).toLowerCase());

//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS, radarParameters.getAttributeString("beams", defStr)); // Included in Radarsat 2 version!

    // Extract Radar Centre Frequency
    final MetadataElement radarCentreFrequency = sourceAttributes.getElement("RadarCentreFrequency");
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency, radarCentreFrequency.getAttributeDouble("RadarCentreFrequency", defInt) / Constants.oneMillion);

    // Extract Acquisition ID (data take ID)
    final int dataTakeID = sourceAttributes.getAttributeInt("AcquisitionID");
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.data_take_id, dataTakeID);

    // Extract Pass direction
    final String passDirection = orbitData.getAttributeString("Pass_Direction", defStr).toUpperCase();
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, passDirection);

    // Extract Algorithm Used
    final String algorithmUsed = imageGenerationParameters.getAttributeString("AlgorithmUsed", defStr);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.algorithm, algorithmUsed);

    // Extract Ellipsoid Name (geo ref system)
    final String geoRefSystem = geographicInformation.getAttributeString("EllipsoidName", defStr);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.geo_ref_system, geoRefSystem);

    // Set Antenna Elevation Correction Flag (we ALWAYS apply elevation beam correction)
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag, 1);

    // Set Range Spread Compensation Flag (we ALWAYS apply range spread (Rs^3) compensation)
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag, 1);

    // Set Replica Power Correction Flag (we ALWAYS apply replica power correction)
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.replica_power_corr_flag, 1);

    // Create Pass Direction String for use later in constructing product name
    String passStr = "DES";
    if (passDirection.equals("ASCENDING")) passStr = "ASC";

    // Extract Orbit Data Filename
    final String orbitDataFile = orbitData.getAttributeString("OrbitDataFile", defStr);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.orbit_state_vector_file, orbitDataFile);

    // Extract the number after the last '_' and before the '.' as in '.xml' in the orbit data file
    //Integer temp = Integer.parseInt(orbitDataFile.substring(orbitDataFile.lastIndexOf('_') + 1, orbitDataFile.indexOf('.')).trim());
    //AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT, temp);

    // Extract Product Type
    final String productType = imageGenerationParameters.getAttributeString("ProductType", defStr);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType.toUpperCase());
    setSLC(false);
    if (productType.toUpperCase().contains("SLC")) setSLC(true);

    // Extract (from product Type) whether it is a Ground Range or Slant Range image
    if ((productType.toUpperCase().contains("GRD")) || (productType.toUpperCase().contains("SCD")))
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 1); // Ground Range
    else AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, 0); // Slant Range

    // Extract Product Name
    final String productName = productAttributes.getAttributeString("ProductName", defStr);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, productName);

    // Extract Mission (satellite) Name
    final String satellite = sourceAttributes.getAttributeString("Satellite", defStr);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, satellite);

    // Extract Operational Mode Name
    final String opModeName = sourceAttributes.getAttributeString("OperationalModeName", defStr);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR, opModeName);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE, opModeName);

    // Extract the Beams/Swaths from the end of the opModeName (string after the last '_')
    String swaths = opModeName.substring(opModeName.lastIndexOf('_') + 1, opModeName.length());
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS, swaths);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SWATH, swaths);

    // Extract the Calibration Status
    String calStatus = imageAttributes.getAttributeString("CalibrationStatus", defStr);
    if (calStatus.equalsIgnoreCase("CALIBRATED"))
    {
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.abs_calibration_flag, 1);
        double calConstant = imageAttributes.getAttributeDouble("CalibrationConstant", 1.0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.calibration_factor, calConstant);
    }
    else
    {
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.abs_calibration_flag, 0);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.calibration_factor, 1.0);
    }

    // Extract Radiometric Scaling and set incidence angle compensation flag accordingly
    String radScaling = imageGenerationParameters.getAttributeString("RadiometricScaling");
    if (radScaling.equalsIgnoreCase("Sigma0")) AbstractMetadata.setAttribute(absRoot, AbstractMetadata.inc_angle_comp_flag, 1);
    else AbstractMetadata.setAttribute(absRoot, AbstractMetadata.inc_angle_comp_flag, 0);

    // Extract Echo Sampling Rate (Range Sampling Rate)
    MetadataElement esrElem = sourceAttributes.getElement("EchoSamplingRate");
    double echoSamplingRate;
    if (esrElem != null)
    {
        echoSamplingRate = esrElem.getAttributeDouble("EchoSamplingRate", 99999.9) / Constants.oneMillion;
    }
    else echoSamplingRate = 99999.9;
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_sampling_rate, echoSamplingRate);

    // Extract Orbit Data Source
    final String orbitDataSource = orbitData.getAttributeString("OrbitDataSource", defStr);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.VECTOR_SOURCE, orbitDataSource);

    // Extract Processing Facility and Software Version
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier, imageGenerationParameters.getAttributeString("ProcessingFacility", defStr) + '-' + imageGenerationParameters.getAttributeString("SoftwareVersion", defStr));

    // Extract Processing Time
    final String processingTime = imageGenerationParameters.getAttributeString("ProcessingTime");
    ProductData.UTC processingTimeUTC = AbstractMetadata.parseUTC(processingTime, standardDateFormat);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME, processingTimeUTC);

//   AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag, getFlag(sarProcessingInformation, "elevationPatternCorrection")); // Included in Radarsat 2 version!
//   AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag, getFlag(sarProcessingInformation, "rangeSpreadingLossCorrection")); // Included in Radarsat 2 version!

    // Extract First Line Time
    final String startTimeString = imageGenerationParameters.getAttributeString("ZeroDopplerTimeFirstLine");
    ProductData.UTC startTime = AbstractMetadata.parseUTC(startTimeString, standardDateFormat);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, startTime);

    // Extract Last Line Time
    final String stopTimeString = imageGenerationParameters.getAttributeString("ZeroDopplerTimeLastLine");
    ProductData.UTC stopTime = AbstractMetadata.parseUTC(stopTimeString, standardDateFormat);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, stopTime);

    // Extract Number of Range Looks
    int numRangeLooks = Integer.parseInt(imageGenerationParameters.getAttributeString("NumberOfRangeLooks", defStr));
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks, numRangeLooks);

    // Extract Number of Azimuth Looks
    int numAzimuthLooks = Integer.parseInt(imageGenerationParameters.getAttributeString("NumberOfAzimuthLooks", defStr));
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks, numAzimuthLooks);

    // Set multi-look flag according to number of looks
    if ((numRangeLooks > 1) || (numAzimuthLooks > 1)) AbstractMetadata.setAttribute(absRoot, AbstractMetadata.multilook_flag, 1);
    else AbstractMetadata.setAttribute(absRoot, AbstractMetadata.multilook_flag, 0);

       // Extract Slant Range of Near Edge
    final MetadataElement srtfpElem = imageGenerationParameters.getElement("SlantRangeNearEdge");
    final double slant_range_to_first_pixel = srtfpElem.getAttributeDouble("SlantRangeNearEdge",0.0);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slant_range_to_first_pixel, slant_range_to_first_pixel);

    // Extract Total Processed Range Bandwidth
    final MetadataElement totalProcessedRangeBandwidth = imageGenerationParameters.getElement("TotalProcessedRangeBandwidth");
    final double rangeBW = totalProcessedRangeBandwidth.getAttributeDouble("TotalProcessedRangeBandwidth"); // Hz
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_bandwidth, rangeBW / Constants.oneMillion); // in MHz

    // Extract Total Processed Azimuth Bandwidth
    final MetadataElement totalProcessedAzimuthBandwidth = imageGenerationParameters.getElement("TotalProcessedAzimuthBandwidth");
    double azimuthBW = totalProcessedAzimuthBandwidth.getAttributeDouble("TotalProcessedAzimuthBandwidth"); // Hz
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_bandwidth, azimuthBW); // in Hz

    // Extract Data Type (COMPLEX, MAGNITUDE_DETECTED ...)
    String dt = imageAttributes.getAttributeString("DataType", defStr);
    if (dt.contains("MAGNITUDE_DETECTED")) dt = "DETECTED";
    else dt = "COMPLEX";
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, dt);

    // Verify Product Format (e.g. GEOTIFF)
    final String pf = imageAttributes.getAttributeString("ProductFormat", defStr);
    verifyProductFormat(pf);

    // Extract Number of Lines in Image
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines, imageAttributes.getAttributeInt("NumberOfLinesInImage", defInt));

    // Extract Number of Samples Per Line
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line, imageAttributes.getAttributeInt("NumberOfSamplesPerLine", defInt));

    // Determine Line Time Interval
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval, ReaderUtils.getLineTimeInterval(startTime, stopTime, absRoot.getAttributeInt(AbstractMetadata.num_output_lines)));

    // Extract Range (Sample) Pixel Spacing
    final MetadataElement spsElem = imageAttributes.getElement("SampledPixelSpacing");
    final double sampledPixelSpacing = spsElem.getAttributeDouble("SampledPixelSpacing",0.0);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing, sampledPixelSpacing);

    // Extract Azimuth (Line) Pixel Spacing
    final MetadataElement slsElem = imageAttributes.getElement("SampledLineSpacing");
    final double sampledLineSpacing = slsElem.getAttributeDouble("SampledLineSpacing",0.0);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing, sampledLineSpacing);

    // Extract Pulse Repetition Frequency (PRF)
    final MetadataElement pulseRepetitionFrequency = sourceAttributes.getElement("PulseRepetitionFrequency");
    double prf = pulseRepetitionFrequency.getAttributeDouble("pulseRepetitionFrequency", defInt);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency, prf);

    // Extract Mean Terrain Height
    final MetadataElement meanTerrainHeight = geographicInformation.getElement("MeanTerrainHeight");
    double mth = meanTerrainHeight.getAttributeDouble("MeanTerrainHeight", defInt);
    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.avg_scene_height, mth);

    // Get Image Polarisations included in this product
    getPolarizations(absRoot, imageAttributes);

    // Add Orbit State Vectors
    addOrbitStateVectors(absRoot, orbitData);

    // Add Slant Range to Ground Range Coefficients
    if ((productType.toUpperCase().contains("GRD")) || (productType.toUpperCase().contains("SCD"))) addSRGRCoefficients(absRoot, imageGenerationParameters); // Should ONLY be called for Ground Range images as the line containing the coefficients is not present in the metadata file for slant range products!

    // Add Doppler Centroid Coefficients (Actually just a single coefficient that is independent of time!!)

    final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);
    int listCnt = 1;
    final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + '.' + listCnt);
    dopplerCentroidCoefficientsElem.addElement(dopplerListElem);
    ++listCnt;

    dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, startTime); // Set the time value in the new element TODO CHECK IF THIS IS THE RIGHT TIME TO USE

    double refTime = 0.0; // TODO CHECK IF IT IS OK TO SET THIS TO ZERO
    AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,ProductData.TYPE_FLOAT64, "ns", "Slant Range Time"); // Add a slant range time attribute to the element
    AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time, refTime); // Set the value of the slant range time attribute to zero

       MetadataElement dopCoeffElem = imageGenerationParameters.getElement("DopplerCentroid");
    String coeffStr = dopCoeffElem.getAttributeString("DopplerCentroid"); // Get the string containing all the coefficients from the original metadata
    if (!coeffStr.isEmpty())
       {
           final StringTokenizer st = new StringTokenizer(coeffStr);
           int cnt = 1;
           while (st.hasMoreTokens())
           {
               final double coefValue = Double.parseDouble(st.nextToken());

               final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
               dopplerListElem.addElement(coefElem);
               ++cnt;
               AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");
               AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, coefValue);
           }
       }

} // End of addAbstractedMetadataHeader()


//========================================================================================================================================================================================
// Function to 
//========================================================================================================================================================================================
private void verifyProductFormat(String prodForm) throws IOException
    {
        if (!prodForm.equalsIgnoreCase("GeoTIFF"))
        {
            throw new IOException("NovaSAR " + prodForm + " format is not supported by this reader");
        }
    } // End of verifyProductFormat()

//========================================================================================================================================================================================
// Function to 
//========================================================================================================================================================================================
private static int getFlag(final MetadataElement elem, String tag)
    {
        String valStr = elem.getAttributeString(tag, " ").toUpperCase();
        if (valStr.equals("FALSE") || valStr.equals("0"))
            return 0;
        else if (valStr.equals("TRUE") || valStr.equals("1"))
            return 1;
        return -1;
    } // End of getFlag()

//========================================================================================================================================================================================
// Function to analyse fullResolutionImageData entries in the metadata and extract the polarisation cases that are present, loading them into the polarisation tags in the SNAP structures
//========================================================================================================================================================================================
private void getPolarizations(final MetadataElement absRoot, final MetadataElement imageAttributes)
    {
        final MetadataElement[] imageAttribElems = imageAttributes.getElements();
        int i = 0;
        for (MetadataElement elem : imageAttribElems)
        {
            if (elem.getName().equals("fullResolutionImageData"))
            {
                final String pol = elem.getAttributeString("Pol", "").toUpperCase();
                polarizationMap.put(elem.getAttributeString("fullResolutionImageData", "").toLowerCase(), pol);
                absRoot.setAttributeString(AbstractMetadata.polarTags[i], pol);
                ++i;
            }
        }
    } // End of getPolarizations()

//==========================================================================================================================================
// Function to analyse the dataType metadata element and return its contents as either "COMPLEX" or "DETECTED"
//==========================================================================================================================================
//private static String getDataType(final MetadataElement rasterAttributes)
//    {
//
//        final String dataType = rasterAttributes.getAttributeString("dataType", AbstractMetadata.NO_METADATA_STRING).toUpperCase();
//        if (dataType.contains("COMPLEX")) return "COMPLEX";
//        return "DETECTED";
//
//    } // End of getDataType()


//==========================================================================================================================================
// Function to read in all the orbit state vectors from the metadata file and load them into the SNAP structures using calls to addVector()
//==========================================================================================================================================
private static void addOrbitStateVectors(final MetadataElement absRoot, final MetadataElement orbitData)
    {
        final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);

        final int numVectors = orbitData.getAttributeInt("NumberOfStateVectorSets");

        final MetadataElement[] stateVectorElems = orbitData.getElements();
        for (int i = 1; i <= numVectors; i++)
        {
            addVector(AbstractMetadata.orbit_vector, orbitVectorListElem, stateVectorElems[i - 1], i);
        }

        // set state vector time
        if (absRoot.getAttributeUTC(AbstractMetadata.STATE_VECTOR_TIME, AbstractMetadata.NO_METADATA_UTC).equalElems(AbstractMetadata.NO_METADATA_UTC))
        {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME, ReaderUtils.getTime(stateVectorElems[0], "Time", standardDateFormat));
        }

    } // End of addOrbitStateVectors()


//========================================================================================================
// Function to extract the parameters of an orbit State Vector from the metadata and load them into SNAP 
//========================================================================================================
private static void addVector(String name, MetadataElement orbitVectorListElem,MetadataElement srcElem, int num)
    {
        final MetadataElement orbitVectorElem = new MetadataElement(name + num);

        orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time,ReaderUtils.getTime(srcElem, "Time", standardDateFormat));

        final MetadataElement xpos = srcElem.getElement("xPosition");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos,xpos.getAttributeDouble("xPosition", 0));
        final MetadataElement ypos = srcElem.getElement("yPosition");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos,ypos.getAttributeDouble("yPosition", 0));
        final MetadataElement zpos = srcElem.getElement("zPosition");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos,zpos.getAttributeDouble("zPosition", 0));
        final MetadataElement xvel = srcElem.getElement("xVelocity");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel,xvel.getAttributeDouble("xVelocity", 0));
        final MetadataElement yvel = srcElem.getElement("yVelocity");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel,yvel.getAttributeDouble("yVelocity", 0));
        final MetadataElement zvel = srcElem.getElement("zVelocity");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel,zvel.getAttributeDouble("zVelocity", 0));

        orbitVectorListElem.addElement(orbitVectorElem);

    } // End of addVector()

//=============================================================================================================
// Function to extract the Ground Range to Slant Range coefficients from the metadata and load them into SNAP
//=============================================================================================================
private static void addSRGRCoefficients(final MetadataElement absRoot, final MetadataElement imageGenerationParameters)
    {
        final MetadataElement srgrCoefficientsElem = absRoot.getElement(AbstractMetadata.srgr_coefficients);

        int listCnt = 1;

        final String zdtfl = imageGenerationParameters.getAttributeString("ZeroDopplerTimeFirstLine");
        ProductData.UTC zdtfl_utc = AbstractMetadata.parseUTC(zdtfl, standardDateFormat);

//        ProductData.UTC zdtfl_utc = ReaderUtils.getTime(zdtfl_elem, "Value", standardDateFormat);

        final MetadataElement srgrListElem = new MetadataElement(AbstractMetadata.srgr_coef_list + '.' + listCnt);
        srgrCoefficientsElem.addElement(srgrListElem);
        ++listCnt;

        srgrListElem.setAttributeUTC(AbstractMetadata.srgr_coef_time, zdtfl_utc);

        //final MetadataElement srgrListElem = new MetadataElement(AbstractMetadata.srgr_coef_list + '.' + listCnt);
        //srgrCoefficientsElem.addElement(srgrListElem);

        final double grOrigin = 0.0; // elem.getElement("groundRangeOrigin").getAttributeDouble("groundRangeOrigin", 0); // No parameter in NovaSAR metadata file for this. Must always be zero for NovaSAR.
        AbstractMetadata.addAbstractedAttribute(srgrListElem, AbstractMetadata.ground_range_origin,ProductData.TYPE_FLOAT64, "m", "Ground Range Origin");
        AbstractMetadata.setAttribute(srgrListElem, AbstractMetadata.ground_range_origin, grOrigin);

        final String gtosrcoeffsString = imageGenerationParameters.getAttributeString("GroundToSlantRangeCoefficients");
        if (!gtosrcoeffsString.isEmpty())
                {
                    StringTokenizer st = new StringTokenizer(gtosrcoeffsString);
                    int cnt = 1;
                    while (st.hasMoreTokens())
                    {
                        final double coefValue = Double.parseDouble(st.nextToken());

                        final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
                        srgrListElem.addElement(coefElem);
                        ++cnt;
                        AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.srgr_coef,ProductData.TYPE_FLOAT64, "", "SRGR Coefficient");
                        AbstractMetadata.setAttribute(coefElem, AbstractMetadata.srgr_coef, coefValue);
                    }
                }

    } // End of addSRGRCoefficients()

//========================================================================================================================================================================================
// Function to 
//========================================================================================================================================================================================
private static void addDopplerCentroidCoefficients(final MetadataElement absRoot, final MetadataElement imageGenerationParameters) // NOT CALLED IN NOVASAR VERSION!!!!!
    {
        final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);

        int listCnt = 1;
        for (MetadataElement elem : imageGenerationParameters.getElements())
        {
            if (elem.getName().equalsIgnoreCase("DopplerCentroid"))
            {
                final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + '.' + listCnt);
                dopplerCentroidCoefficientsElem.addElement(dopplerListElem);
                ++listCnt;

                final String zdtfl = imageGenerationParameters.getAttributeString("ZeroDopplerTimeFirstLine");
                ProductData.UTC utcTime = AbstractMetadata.parseUTC(zdtfl, standardDateFormat);
                dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);

                final double refTime = elem.getElement("dopplerCentroidReferenceTime").getAttributeDouble("dopplerCentroidReferenceTime", 0) * 1e9; // s to ns
                AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");
                AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time, refTime);

                final String coeffStr = elem.getAttributeString("dopplerCentroidCoefficients", "");
                if (!coeffStr.isEmpty())
                {
                    final StringTokenizer st = new StringTokenizer(coeffStr);
                    int cnt = 1;
                    while (st.hasMoreTokens())
                    {
                        final double coefValue = Double.parseDouble(st.nextToken());

                        final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
                        dopplerListElem.addElement(coefElem);
                        ++cnt;
                        AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");
                        AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, coefValue);
                    }
                }
            }
        }
    } // End of addDopplerCentroidCoefficients()

//========================================================================================================================================================================================
// Function to 
//========================================================================================================================================================================================
@Override
protected void addGeoCoding(final Product product)
       {

        MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final boolean isAscending = absRoot.getAttributeString(AbstractMetadata.PASS).equals("ASCENDING");
        final boolean isAntennaPointingRight = absRoot.getAttributeString(AbstractMetadata.antenna_pointing).equals("right");

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement origMetaData = origProdRoot.getElement("metadata");
 //       final MetadataElement productElem = origMetaData.getElement("Product");
 //       final MetadataElement imageAttributes = origMetaData.getElement("Image_Attributes");
        final MetadataElement geographicInformation = origMetaData.getElement("geographicInformation");

        int gridWidth = 0, gridHeight = 0; // The number of tiepoints in the grid in width (pixels) and height (lines)

        // Get number of Range Tiepoints
        int numRangeTiepoints = geographicInformation.getAttributeInt("NumberOfRangeTiepoints", defInt);
        gridWidth = numRangeTiepoints;

        // Get number of Azimuth Tiepoints
        int numAzimuthTiepoints = geographicInformation.getAttributeInt("NumberOfAzimuthTiepoints", defInt);
        gridHeight = numAzimuthTiepoints;

        // Compute total number of tiepoints
        int numberOfTiepoints = numRangeTiepoints * numAzimuthTiepoints;

        float[] latList = new float[numberOfTiepoints];
        float[] lngList = new float[numberOfTiepoints];

        int i = 0;
        for (MetadataElement elem : geographicInformation.getElements()) // For all elements in the Geographic Information
        {
            if (elem.getName().equalsIgnoreCase("TiePoint")) // If this element is a Tie Point
            {
                final MetadataElement latitude = elem.getElement("latitude");
                final MetadataElement longitude = elem.getElement("longitude");

                latList[i] = (float) latitude.getAttributeDouble("latitude", 0);
                lngList[i] = (float) longitude.getAttributeDouble("longitude", 0);

                ++i;
            }
        }

        if (flipToSARGeometry)
          {
            float[] flippedLatList = new float[numberOfTiepoints];
            float[] flippedLonList = new float[numberOfTiepoints];
            int is, id;
            if (isAscending)
               {
                if (isAntennaPointingRight)
                { // flip upside down
                    for (int r = 0; r < gridHeight; r++)
                    {
                        is = r * gridWidth;
                        id = (gridHeight - r - 1) * gridWidth;
                        for (int c = 0; c < gridWidth; c++)
                        {
                            flippedLatList[id + c] = latList[is + c];
                            flippedLonList[id + c] = lngList[is + c];
                        }
                    }
                }
                else
                { // flip upside down then left to right
                    for (int r = 0; r < gridHeight; r++)
                    {
                        is = r * gridWidth;
                        id = (gridHeight - r) * gridWidth;
                        for (int c = 0; c < gridWidth; c++)
                        {
                            flippedLatList[id - c - 1] = latList[is + c];
                            flippedLonList[id - c - 1] = lngList[is + c];
                        }
                    }
                }

            }
            else
            { // descending

                if (isAntennaPointingRight) {  // flip left to right
                    for (int r = 0; r < gridHeight; r++)
                    {
                        is = r * gridWidth;
                        id = r * gridWidth + gridWidth;
                        for (int c = 0; c < gridWidth; c++)
                        {
                            flippedLatList[id - c - 1] = latList[is + c];
                            flippedLonList[id - c - 1] = lngList[is + c];
                        }
                    }
                }
                else
                { // no flipping is needed
                    flippedLatList = latList;
                    flippedLonList = lngList;
                }
            }

            latList = flippedLatList;
            lngList = flippedLonList;
        }

        double subSamplingX = (double) (product.getSceneRasterWidth() - 1) / (gridWidth - 1);
        double subSamplingY = (double) (product.getSceneRasterHeight() - 1) / (gridHeight - 1);

        final TiePointGrid latGrid = new TiePointGrid(OperatorUtils.TPG_LATITUDE, gridWidth, gridHeight, 0.5f, 0.5f,subSamplingX, subSamplingY, latList);
        latGrid.setUnit(Unit.DEGREES);

        final TiePointGrid lonGrid = new TiePointGrid(OperatorUtils.TPG_LONGITUDE, gridWidth, gridHeight, 0.5f, 0.5f,subSamplingX, subSamplingY, lngList, TiePointGrid.DISCONT_AT_180);
        lonGrid.setUnit(Unit.DEGREES);

        final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);

        product.addTiePointGrid(latGrid);
        product.addTiePointGrid(lonGrid);
        product.setSceneGeoCoding(tpGeoCoding);

        setLatLongMetadata(product, latGrid, lonGrid);

    } // End of addGeoCoding()

//====================================================================================================================
// Function to set image corner tiepoint longitudes and latitudes in the SNAP structures 
//
//       - called by addGeoCoding()
//====================================================================================================================
private static void setLatLongMetadata(Product product, TiePointGrid latGrid, TiePointGrid lonGrid)
    {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final int w = product.getSceneRasterWidth();
        final int h = product.getSceneRasterHeight();

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_lat, latGrid.getPixelDouble(0, 0));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_long, lonGrid.getPixelDouble(0, 0));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_lat, latGrid.getPixelDouble(w - 1, 0));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_long, lonGrid.getPixelDouble(w - 1, 0));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_lat, latGrid.getPixelDouble(0, h - 1));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_long, lonGrid.getPixelDouble(0, h - 1));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_lat, latGrid.getPixelDouble(w - 1, h - 1));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_long, lonGrid.getPixelDouble(w - 1, h - 1));
    }

//========================================================================================================================================================================================
// Function to 
//========================================================================================================================================================================================
@Override
protected void addTiePointGrids(final Product product)
       {

        final int sourceImageWidth = product.getSceneRasterWidth();
        final int sourceImageHeight = product.getSceneRasterHeight();
        final int gridWidth = 11;
        final int gridHeight = 11;
        final int subSamplingX = (int) ((float) sourceImageWidth / (float) (gridWidth - 1));
        final int subSamplingY = (int) ((float) sourceImageHeight / (float) (gridHeight - 1));

        double a = Constants.semiMajorAxis; // WGS 84: equatorial Earth radius in m
        double b = Constants.semiMinorAxis; // WGS 84: polar Earth radius in m

        // Get slant range to first pixel and pixel spacing
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final double slantRangeToFirstPixel = absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel, 0); // in m
        final double rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing, 0); // in m
        final boolean srgrFlag = absRoot.getAttributeInt(AbstractMetadata.srgr_flag) != 0;
        final boolean isDescending = absRoot.getAttributeString(AbstractMetadata.PASS).equals("DESCENDING");
        final boolean isAntennaPointingRight = absRoot.getAttributeString(AbstractMetadata.antenna_pointing).equals("right");

        // Get scene center latitude
        final GeoPos sceneCenterPos = product.getSceneGeoCoding().getGeoPos(new PixelPos(sourceImageWidth / 2.0f, sourceImageHeight / 2.0f), null);
        double sceneCenterLatitude = sceneCenterPos.lat; // in deg

        // Get near range incidence angle
        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement origMetaData = origProdRoot.getElement("metadata");
        final MetadataElement imageGenerationParameters = origMetaData.getElement("Image_Generation_Parameters");
        final String incAngleCoeffs = imageGenerationParameters.getAttributeString("IncAngleCoeffs",defStr); // Get the Incidence Angle Coefficients string
        StringTokenizer st = new StringTokenizer(incAngleCoeffs); // Convert it to a tokeniser
        final double nearRangeIncidenceAngle = Double.parseDouble(st.nextToken()); // Extract the first token substring (which is the near edge incidence angle) as a double

        final double alpha1 = nearRangeIncidenceAngle * Constants.DTOR;
        final double lambda = sceneCenterLatitude * Constants.DTOR;
        final double cos2 = FastMath.cos(lambda) * FastMath.cos(lambda);
        final double sin2 = FastMath.sin(lambda) * FastMath.sin(lambda);
        final double e2 = (b * b) / (a * a);
        final double rt = a * Math.sqrt((cos2 + e2 * e2 * sin2) / (cos2 + e2 * sin2));
        final double rt2 = rt * rt;

        double groundRangeSpacing;
        if (srgrFlag)
        { // ground range - so use as is
            groundRangeSpacing = rangeSpacing;
        }
        else
        { // slant range - so convert to ground range
            groundRangeSpacing = rangeSpacing / FastMath.sin(alpha1);
        }

        double deltaPsi = groundRangeSpacing / rt; // in radians
        final double r1 = slantRangeToFirstPixel;
        final double rtPlusH = Math.sqrt(rt2 + r1 * r1 + 2.0 * rt * r1 * FastMath.cos(alpha1));
        final double rtPlusH2 = rtPlusH * rtPlusH;
        final double theta1 = FastMath.acos((r1 + rt * FastMath.cos(alpha1)) / rtPlusH);
        final double psi1 = alpha1 - theta1;
        double psi = psi1;
        float[] incidenceAngles = new float[gridWidth];
        final int n = gridWidth * subSamplingX;
        int k = 0;
        for (int i = 0; i < n; i++)
        {
            final double ri = Math.sqrt(rt2 + rtPlusH2 - 2.0 * rt * rtPlusH * FastMath.cos(psi));
            final double alpha = FastMath.acos((rtPlusH2 - ri * ri - rt2) / (2.0 * ri * rt));
            if (i % subSamplingX == 0)
            {
                int index = k++;

                if (!flipToSARGeometry && (isDescending && isAntennaPointingRight || (!isDescending && !isAntennaPointingRight))) {// flip
                    index = gridWidth - 1 - index;
                }

                incidenceAngles[index] = (float) (alpha * Constants.RTOD);
            }

            if (!srgrFlag) { // complex
                groundRangeSpacing = rangeSpacing / FastMath.sin(alpha);
                deltaPsi = groundRangeSpacing / rt;
            }
            psi = psi + deltaPsi;
        }

        float[] incidenceAngleList = new float[gridWidth * gridHeight];
        for (int j = 0; j < gridHeight; j++)
        {
            System.arraycopy(incidenceAngles, 0, incidenceAngleList, j * gridWidth, gridWidth);
        }

        final TiePointGrid incidentAngleGrid = new TiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE, gridWidth, gridHeight, 0, 0,subSamplingX, subSamplingY, incidenceAngleList);

        incidentAngleGrid.setUnit(Unit.DEGREES);

        product.addTiePointGrid(incidentAngleGrid);

        //addSlantRangeTime(product, imageGenerationParameters);

    } // End of addTiePointGrids()

//========================================================================================================================================================================================
// Function to 
//
//   - called by addTiePointGrids()
//========================================================================================================================================================================================
private static void addSlantRangeTime(final Product product, final MetadataElement imageGenerationParameters)
   {
        class coefList
        {
            double utcSeconds = 0.0;
            double grOrigin = 0.0;
            final List<Double> coefficients = new ArrayList<>();
        }

        final List<coefList> segmentsArray = new ArrayList<>();


       final String coeffStr = imageGenerationParameters.getAttributeString("GroundToSlantRangeCoefficients", "");

       coefList coef = new coefList();
       segmentsArray.add(coef);
      // coef.utcSeconds = ReaderUtils.getTime(elem, "zeroDopplerAzimuthTime", standardDateFormat).getMJD() * 24 * 3600;
      // coef.grOrigin = elem.getElement("groundRangeOrigin").getAttributeDouble("groundRangeOrigin", 0);

       if (!coeffStr.isEmpty())
                {
                    final StringTokenizer st = new StringTokenizer(coeffStr);
                    while (st.hasMoreTokens()) {
                        coef.coefficients.add(Double.parseDouble(st.nextToken()));
                    }
                }


        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final double lineTimeInterval = absRoot.getAttributeDouble(AbstractMetadata.line_time_interval, 0);
        final ProductData.UTC startTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time, AbstractMetadata.NO_METADATA_UTC);
        final double startSeconds = startTime.getMJD() * 24 * 3600;
        final double pixelSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing, 0);
        final boolean isDescending = absRoot.getAttributeString(AbstractMetadata.PASS).equals("DESCENDING");
        final boolean isAntennaPointingRight = absRoot.getAttributeString(AbstractMetadata.antenna_pointing).equals("right");

        final int gridWidth = 11;
        final int gridHeight = 11;
        final int sceneWidth = product.getSceneRasterWidth();
        final int sceneHeight = product.getSceneRasterHeight();
        final int subSamplingX = sceneWidth / (gridWidth - 1);
        final int subSamplingY = sceneHeight / (gridHeight - 1);
        final float[] rangeDist = new float[gridWidth * gridHeight];
        final float[] rangeTime = new float[gridWidth * gridHeight];

        final coefList[] segments = segmentsArray.toArray(new coefList[segmentsArray.size()]);

        int k = 0;
        int c = 0;
        for (int j = 0; j < gridHeight; j++)
        {
            final double time = startSeconds + (j * lineTimeInterval);
            while (c < segments.length && segments[c].utcSeconds < time)
                ++c;
            if (c >= segments.length)
                c = segments.length - 1;

            coef = segments[c];
            final double GR0 = coef.grOrigin;
            final double s0 = coef.coefficients.get(0);
            final double s1 = coef.coefficients.get(1);
            final double s2 = coef.coefficients.get(2);
            final double s3 = coef.coefficients.get(3);
            final double s4 = coef.coefficients.get(4);

            for (int i = 0; i < gridWidth; i++)
            {
                int x = i * subSamplingX;
                final double GR = x * pixelSpacing;
                final double g = GR - GR0;
                final double g2 = g * g;

                //SlantRange = s0 + s1(GR - GR0) + s2(GR-GR0)^2 + s3(GRGR0)^3 + s4(GR-GR0)^4;
                rangeDist[k++] = (float) (s0 + s1 * g + s2 * g2 + s3 * g2 * g + s4 * g2 * g2);
            }
        }

        // get slant range time in nanoseconds from range distance in meters
        for (int i = 0; i < rangeDist.length; i++)
        {
            int index = i;
            if (!flipToSARGeometry && (isDescending && isAntennaPointingRight || !isDescending && !isAntennaPointingRight)) // flip for descending RS2
                index = rangeDist.length - 1 - i;

            rangeTime[index] = (float) (rangeDist[i] / Constants.halfLightSpeed * Constants.oneBillion); // in ns
        }

        final TiePointGrid slantRangeGrid = new TiePointGrid(OperatorUtils.TPG_SLANT_RANGE_TIME,gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, rangeTime);

        product.addTiePointGrid(slantRangeGrid);
        slantRangeGrid.setUnit(Unit.NANOSECONDS);

    } // End of addSlantRangeTime()

//========================================================================================================================================================================================
// Function to return the Mission name  (NOT USED IN NOVASAR VERSION)
//========================================================================================================================================================================================
private static String getMission()
    {
        return NovaSARConstants.MISSION_NAME;
    }

//========================================================================================================================================================================================
// Function to return the product name
//========================================================================================================================================================================================
@Override
protected String getProductName()
    {
        return productName;
    }

//========================================================================================================================================================================================
// Function to return the product description
//========================================================================================================================================================================================
@Override
protected String getProductDescription()
    {
        return productDescription;
    }

//========================================================================================================================================================================================
// Function to return the product type
//========================================================================================================================================================================================
@Override
protected String getProductType()
    {
        return productType;
    }

} // End of Class NovaSARProductDirectory
