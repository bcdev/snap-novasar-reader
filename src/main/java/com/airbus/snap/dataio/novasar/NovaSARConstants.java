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
package com.airbus.snap.dataio.novasar;

import java.io.File;

/**
 * Several constants used for reading NovaSAR products.
 */
class NovaSARConstants {

    private final static String[] FORMAT_NAMES = new String[]{"NOVASAR"};
    private final static String[] FORMAT_FILE_EXTENSIONS = new String[]{"xml", "zip"};
    private final static String PLUGIN_DESCRIPTION = "NOVASAR Products";
    
    final static String PRODUCT_HEADER_NAME = "metadata.xml";
    final static String PRODUCT_HEADER_PREFIX = "METADATA";
    final static String MISSION_NAME = "NOVS";

    private final static String INDICATION_KEY = "XML";

    final static Class[] VALID_INPUT_TYPES = new Class[]{File.class, String.class};

    static String getIndicationKey() {
        return INDICATION_KEY;
    }

    static String getPluginDescription() {
        return PLUGIN_DESCRIPTION;
    }

    static String[] getFormatNames() {
        return FORMAT_NAMES;
    }

    static String[] getFormatFileExtensions() {
        return FORMAT_FILE_EXTENSIONS;
    }

}
