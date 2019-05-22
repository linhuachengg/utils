package com.example.live.myapplication;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.res.Resources;



public class BitMapLoadUtil {
    public static Bitmap decodeBitmapFromRes(Resources res, int resId, int reqWidth, int reqHeight) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;//只会解析图片宽高信息，不会加载图片
        BitmapFactory.decodeResource(res,resId,options);
        options.inSampleSize = calculatInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        return  BitmapFactory.decodeResource(res, resId, options);

    }

    private static int calculatInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height/2;
            int halfWidth = width/2;
            while (halfHeight/inSampleSize >reqHeight || halfWidth/inSampleSize > reqWidth) {
                inSampleSize *= 2;
            }
        }
        return  inSampleSize;
    }
}
