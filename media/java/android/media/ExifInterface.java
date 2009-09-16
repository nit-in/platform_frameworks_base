/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.media;

import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * This is a class for reading and writing Exif tags in a JPEG file.
 */
public class ExifInterface {

    // The Exif tag names
    public static final String TAG_ORIENTATION = "Orientation";
    public static final String TAG_DATETIME = "DateTime";
    public static final String TAG_MAKE = "Make";
    public static final String TAG_MODEL = "Model";
    public static final String TAG_FLASH = "Flash";
    public static final String TAG_IMAGE_WIDTH = "ImageWidth";
    public static final String TAG_IMAGE_LENGTH = "ImageLength";
    public static final String TAG_GPS_LATITUDE = "GPSLatitude";
    public static final String TAG_GPS_LONGITUDE = "GPSLongitude";
    public static final String TAG_GPS_LATITUDE_REF = "GPSLatitudeRef";
    public static final String TAG_GPS_LONGITUDE_REF = "GPSLongitudeRef";
    public static final String TAG_WHITE_BALANCE = "WhiteBalance";

    // Constants used for the Orientation Exif tag.
    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_NORMAL = 1;
    public static final int ORIENTATION_FLIP_HORIZONTAL = 2;  // left right reversed mirror
    public static final int ORIENTATION_ROTATE_180 = 3;
    public static final int ORIENTATION_FLIP_VERTICAL = 4;  // upside down mirror
    public static final int ORIENTATION_TRANSPOSE = 5;  // flipped about top-left <--> bottom-right axis
    public static final int ORIENTATION_ROTATE_90 = 6;  // rotate 90 cw to right it
    public static final int ORIENTATION_TRANSVERSE = 7;  // flipped about top-right <--> bottom-left axis
    public static final int ORIENTATION_ROTATE_270 = 8;  // rotate 270 to right it

    // Constants used for white balance
    public static final int WHITEBALANCE_AUTO = 0;
    public static final int WHITEBALANCE_MANUAL = 1;

    static {
        System.loadLibrary("exif");
    }

    private String mFilename;
    private HashMap<String, String> mAttributes;
    private boolean mHasThumbnail = false;

    // Because the underlying implementation (jhead) uses static variables,
    // there can only be one user at a time for the native functions (and
    // they cannot keep state in the native code across function calls). We
    // use sLock the serialize the accesses.
    private static Object sLock = new Object();

    /**
     * Reads Exif tags from the specified JPEG file.
     */
    public ExifInterface(String filename) throws IOException {
        mFilename = filename;
        loadAttributes();
    }

    /**
     * Returns the value of the specified tag or {@code null} if there
     * is no such tag in the file.
     *
     * @param tag the name of the tag.
     */
    public String getAttribute(String tag) {
        return mAttributes.get(tag);
    }

    /**
     * Set the value of the specified tag.
     *
     * @param tag the name of the tag.
     * @param value the value of the tag.
     */
    public void setAttribute(String tag, String value) {
        mAttributes.put(tag, value);
    }

    /**
     * Initialize mAttributes with the attributes from the file mFilename.
     *
     * mAttributes is a HashMap which stores the Exif attributes of the file.
     * The key is the standard tag name and the value is the tag's value: e.g.
     * Model -> Nikon. Numeric values are stored as strings.
     *
     * This function also initialize mHasThumbnail to indicate whether the
     * file has a thumbnail inside.
     */
    private void loadAttributes() {
        // format of string passed from native C code:
        // "attrCnt attr1=valueLen value1attr2=value2Len value2..."
        // example:
        // "4 attrPtr ImageLength=4 1024Model=6 FooImageWidth=4 1280Make=3 FOO"
        mAttributes = new HashMap<String, String>();

        String attrStr;
        synchronized (sLock) {
            attrStr = getAttributesNative(mFilename);
        }

        // get count
        int ptr = attrStr.indexOf(' ');
        int count = Integer.parseInt(attrStr.substring(0, ptr));
        // skip past the space between item count and the rest of the attributes
        ++ptr;

        for (int i = 0; i < count; i++) {
            // extract the attribute name
            int equalPos = attrStr.indexOf('=', ptr);
            String attrName = attrStr.substring(ptr, equalPos);
            ptr = equalPos + 1;     // skip past =

            // extract the attribute value length
            int lenPos = attrStr.indexOf(' ', ptr);
            int attrLen = Integer.parseInt(attrStr.substring(ptr, lenPos));
            ptr = lenPos + 1;       // skip pas the space

            // extract the attribute value
            String attrValue = attrStr.substring(ptr, ptr + attrLen);
            ptr += attrLen;

            if (attrName.equals("hasThumbnail")) {
                mHasThumbnail = attrValue.equalsIgnoreCase("true");
            } else {
                mAttributes.put(attrName, attrValue);
            }
        }
    }

    /**
     * Save the tag data into the JPEG file. This is expensive because it involves
     * copying all the JPG data from one file to another and deleting the old file
     * and renaming the other. It's best to use {@link #setAttribute(String,String)} to set all
     * attributes to write and make a single call rather than multiple calls for
     * each attribute.
     */
    public void saveAttributes() throws IOException {
        // format of string passed to native C code:
        // "attrCnt attr1=valueLen value1attr2=value2Len value2..."
        // example:
        // "4 attrPtr ImageLength=4 1024Model=6 FooImageWidth=4 1280Make=3 FOO"
        StringBuilder sb = new StringBuilder();
        int size = mAttributes.size();
        if (mAttributes.containsKey("hasThumbnail")) {
            --size;
        }
        sb.append(size + " ");
        for (Map.Entry<String, String> iter : mAttributes.entrySet()) {
            String key = iter.getKey();
            if (key.equals("hasThumbnail")) {
                // this is a fake attribute not saved as an exif tag
                continue;
            }
            String val = iter.getValue();
            sb.append(key + "=");
            sb.append(val.length() + " ");
            sb.append(val);
        }
        String s = sb.toString();
        synchronized (sLock) {
            saveAttributesNative(mFilename, s);
            commitChangesNative(mFilename);
        }
    }

    /**
     * Returns true if the JPEG file has a thumbnail.
     */
    public boolean hasThumbnail() {
        return mHasThumbnail;
    }

    /**
     * Returns the thumbnail inside the JPEG file, or {@code null} if there is no thumbnail.
     */
    public byte[] getThumbnail() {
        synchronized (sLock) {
            return getThumbnailNative(mFilename);
        }
    }

    /**
     * Returns a human-readable string describing the white balance value. Returns empty
     * string if there is no white balance value or it is not recognized.
     */
    public String getWhiteBalanceString() {
        String value = getAttribute(TAG_WHITE_BALANCE);
        if (value == null) return "";

        int whitebalance;
        try {
            whitebalance = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return "";
        }

        switch (whitebalance) {
            case WHITEBALANCE_AUTO:
                return "Auto";
            case WHITEBALANCE_MANUAL:
                return "Manual";
            default:
                return "";
        }
    }

    /**
     * Returns a human-readable string describing the orientation value. Returns empty
     * string if there is no orientation value or it it not recognized.
     */
    public String getOrientationString() {
        // TODO: this function needs to be localized.
        String value = getAttribute(TAG_ORIENTATION);
        if (value == null) return "";

        int orientation;
        try {
            orientation = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return "";
        }

        String orientationString;
        switch (orientation) {
            case ORIENTATION_NORMAL:
                orientationString = "Normal";
                break;
            case ORIENTATION_FLIP_HORIZONTAL:
                orientationString = "Flipped horizontal";
                break;
            case ORIENTATION_ROTATE_180:
                orientationString = "Rotated 180 degrees";
                break;
            case ORIENTATION_FLIP_VERTICAL:
                orientationString = "Upside down mirror";
                break;
            case ORIENTATION_TRANSPOSE:
                orientationString = "Transposed";
                break;
            case ORIENTATION_ROTATE_90:
                orientationString = "Rotated 90 degrees";
                break;
            case ORIENTATION_TRANSVERSE:
                orientationString = "Transversed";
                break;
            case ORIENTATION_ROTATE_270:
                orientationString = "Rotated 270 degrees";
                break;
            default:
                orientationString = "Undefined";
                break;
        }
        return orientationString;
    }

    /**
     * Returns the latitude and longitude value in a float array. The first element is
     * the latitude, and the second element is the longitude.
     */
    public float[] getLatLong() {
        String latValue = mAttributes.get(ExifInterface.TAG_GPS_LATITUDE);
        String latRef = mAttributes.get(ExifInterface.TAG_GPS_LATITUDE_REF);
        String lngValue = mAttributes.get(ExifInterface.TAG_GPS_LONGITUDE);
        String lngRef = mAttributes.get(ExifInterface.TAG_GPS_LONGITUDE_REF);
        float[] latlng = null;

        if (latValue != null && latRef != null
                && lngValue != null && lngRef != null) {
            latlng = new float[2];
            latlng[0] = convertRationalLatLonToFloat(latValue, latRef);
            latlng[1] = convertRationalLatLonToFloat(lngValue, lngRef);
        }

        return latlng;
    }

    private static SimpleDateFormat sFormatter =
            new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

    /**
     * Returns number of milliseconds since Jan. 1, 1970, midnight GMT.
     * Returns -1 if the date time information if not available.
     */
    public long getDateTime() {
        String dateTimeString = mAttributes.get(TAG_DATETIME);
        if (dateTimeString == null) return -1;

        ParsePosition pos = new ParsePosition(0);
        try {
            Date date = sFormatter.parse(dateTimeString, pos);
            if (date == null) return -1;
            return date.getTime();
        } catch (IllegalArgumentException ex) {
            return -1;
        }
    }

    private static float convertRationalLatLonToFloat(
            String rationalString, String ref) {
        try {
            String [] parts = rationalString.split(",");

            String [] pair;
            pair = parts[0].split("/");
            int degrees = (int) (Float.parseFloat(pair[0].trim())
                    / Float.parseFloat(pair[1].trim()));

            pair = parts[1].split("/");
            int minutes = (int) ((Float.parseFloat(pair[0].trim())
                    / Float.parseFloat(pair[1].trim())));

            pair = parts[2].split("/");
            float seconds = Float.parseFloat(pair[0].trim())
                    / Float.parseFloat(pair[1].trim());

            float result = degrees + (minutes / 60F) + (seconds / (60F * 60F));
            if ((ref.equals("S") || ref.equals("W"))) {
                return -result;
            }
            return result;
        } catch (RuntimeException ex) {
            // if for whatever reason we can't parse the lat long then return
            // null
            return 0f;
        }
    }

    private native boolean appendThumbnailNative(String fileName,
            String thumbnailFileName);

    private native void saveAttributesNative(String fileName,
            String compressedAttributes);

    private native String getAttributesNative(String fileName);

    private native void commitChangesNative(String fileName);

    private native byte[] getThumbnailNative(String fileName);
}
