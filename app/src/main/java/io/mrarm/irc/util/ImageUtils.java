package io.mrarm.irc.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageUtils {

    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
            "(https?://[^\\s\\001\\002\\003\\017\\026\\037]+?\\.(?:jpg|jpeg|png|gif|webp|bmp)(?:\\?[^\\s\\001\\002\\003\\017\\026\\037]*)?)",
            Pattern.CASE_INSENSITIVE
    );

    public static String getImageUrl(String text) {
        if (text == null) return null;
        Matcher matcher = IMAGE_URL_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public static boolean isImageUrl(String url) {
        if (url == null) return false;
        return IMAGE_URL_PATTERN.matcher(url).matches();
    }
}