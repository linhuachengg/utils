package com.example.live.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.annotation.NonNull;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;
import com.jakewharton.disklrucache.DiskLruCache;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public class ImageLoader {
    public static final int MESSAGE_POST_RESULT = 1;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final  int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAX_POOL_SIZE = CPU_COUNT*2 + 1;
    private static  final  long KEEP_LIVE = 10L;
    private static final long DISK_CACHE_SIZE = 1024*1024*50;
    private static final int IO_BUFFER_SIZE =8*1024;
    private static final int DISK_CACHE_INDEX = 0;
    private boolean mISDiskLruCacheCreated = false;

    private static  final ThreadFactory sThreadFactory = new ThreadFactory() {
        private  final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r, "ImageLoader#"+mCount.getAndIncrement());
        }
    };
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE,
            MAX_POOL_SIZE,KEEP_LIVE, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(128), sThreadFactory);
    private Handler mMainHandler = new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            LoaderResult loaderResult = (LoaderResult) msg.obj;
            ImageView imageView = loaderResult.imageView;
            String uri = (String) imageView.getTag(1);
            if (uri.equals(loaderResult.uri)) {
                imageView.setImageBitmap(loaderResult.bitmap);
            } else {
                Log.e("tag","url 路径改变");
            }
        }
    };
    private Context mContext;
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;

    private ImageLoader(Context context ) {
        mContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory()/1024);
        int cacheSize = maxMemory/8;

        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                return value.getRowBytes()*value.getHeight()/1024;
            }
        } ;
        File diskCacheDir = getDiskCacheDir(context, "bitmap");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
        if (getUseableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1,1,DISK_CACHE_SIZE);
                mISDiskLruCacheCreated =true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    public static ImageLoader build(Context context) {
        return new ImageLoader(context);
    }

    private void addBitmapTomemoryCache (String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }
    public void bindBitmap (String uri, ImageView imageView) {
        bindBitmap(uri, imageView,0, 0 );
    }

    private void bindBitmap(final String uri, final ImageView imageView, final int reqW, final int reqH) {
        imageView.setTag(1, uri);
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap1 = loadBitmap(uri, reqW, reqH);
                if (bitmap1 != null ) {
                    LoaderResult loaderResult = new LoaderResult(imageView, uri, bitmap1);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, loaderResult).sendToTarget();

                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(runnable);
    }

    public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) {
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {return  bitmap;}
        try {
            bitmap = loadBitmapFromDiskCache(uri,reqWidth, reqHeight);
            if (bitmap != null) {
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
        } catch (IOException e) {
            e.printStackTrace();
        }
    if (bitmap == null && !mISDiskLruCacheCreated) {
        try {
            bitmap = downloadBitmapFromUrl(uri);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }
    return  bitmap;
    }

    private Bitmap loadBitmapFromMemCache (String url) {
        String key = hashKeyFromUrl(url);
        Bitmap bitmap = getBitmapFromMemCache(key);
        return bitmap;
    }

    private Bitmap loadBitmapFromHttp(String url, int reqW, int reqH) throws IOException {
        if ( Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("cant visit net on UI Thread");
        }

        if (mDiskLruCache == null) { return  null; }
        String key = hashKeyFromUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null) {
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrltoStream(url, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
       return loadBitmapFromDiskCache(url, reqW, reqH);
    }
    public boolean downloadUrltoStream (String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream bufferedOutputStream = null;
        BufferedInputStream bufferedInputStream = null;
        try {
            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            bufferedInputStream = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            bufferedOutputStream = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);
            int b;
            while ((b = bufferedInputStream.read()) != -1) {
                bufferedOutputStream.write(b);
            }
            return true;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (bufferedInputStream != null) {
                try {
                    bufferedInputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (bufferedOutputStream != null) {
                    try {
                        bufferedOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return  false;
    }
    private Bitmap loadBitmapFromDiskCache(String url, int reqW, int reqH) throws IOException {
        if (Looper.getMainLooper() == Looper.myLooper()) { }
        if (mDiskLruCache == null) return null;
        Bitmap bitmap = null;
        String key = hashKeyFromUrl(url);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = BitMapLoadUtil.decodeBitmapFromFileDescriptor(fileDescriptor, reqW, reqH);
            if (bitmap != null) {
                addBitmapTomemoryCache(key, bitmap);
            }
        }
        return bitmap;
    }
    private Bitmap downloadBitmapFromUrl(String urlS) throws MalformedURLException {
        URL url = new URL(urlS);
        URLConnection urlConnection = null;
        Bitmap bitmap = null;
        BufferedInputStream bufferedInputStream = null;
        try {
           urlConnection = url.openConnection();
            bufferedInputStream = new BufferedInputStream(urlConnection.getInputStream());
          bitmap = BitmapFactory.decodeStream(bufferedInputStream);

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if ( bufferedInputStream != null) {
                try {
                    bufferedInputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
         return  bitmap;
    }

    private Bitmap getBitmapFromMemCache(String key) {
        return mMemoryCache.get(key);
    }
    public File getDiskCacheDir (Context context, String uniqueName) {
        boolean externalStrorageAvaulable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final  String cachePath;
        if (externalStrorageAvaulable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath+File.separator+uniqueName);
    }
    private long getUseableSpace(File path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        final StatFs statFs = new StatFs(path.getPath());
        return  statFs.getBlockSize()*statFs.getAvailableBlocks();
    }
    private static class LoaderResult {
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
            this.imageView = imageView;
            this.bitmap =bitmap;
            this.uri = uri;
        }
    }
    private String hashKeyFromUrl(String url) {
        String cacheKey;
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(url.getBytes());
            cacheKey = bytesToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }
       return cacheKey;
    }
    private String bytesToHexString(byte[] bytes) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex =Integer.toHexString(0xff&bytes[i]);
            if (hex.length() == 1) {
                stringBuilder.append('0');
            }
            stringBuilder.append(hex);
        }
        return  stringBuilder.toString();
    }
}
