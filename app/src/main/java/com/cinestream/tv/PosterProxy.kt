package com.cinestream.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Semaphore

/**
 * Safely fetch ISP movie posters using a small memory budget.
 * Only configured, trusted ISP source paths are allowed.
 */
class PosterProxy(private val context:Context){
    private val slots=Semaphore(2)
    private val ext=Regex("(?i)\\.(jpe?g|png|webp)$")
    fun fetch(url:String):WebResourceResponse?{
        if(!ext.containsMatchIn(url)||!Sources.defaults.any{CatalogScanner.allowed(url,it.url)})return null
        if(!slots.tryAcquire())return null
        try{
            val hash=MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                .take(12).joinToString(""){"%02x".format(it)}
            val folder=File(context.cacheDir,"zahrus-posters-small").apply{mkdirs()}
            val cache=File(folder,hash+".jpg")
            val bytes=if(cache.exists()&&cache.length() in 1..350000)cache.readBytes()
            else{
                val conn=URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects=false
                conn.connectTimeout=4000
                conn.readTimeout=5500
                val raw=try{
                    if(conn.responseCode!=200||conn.contentLengthLong>1500000L)return null
                    conn.inputStream.use{it.readNBytes(1500001)}
                }finally{conn.disconnect()}
                if(raw.isEmpty()||raw.size>1500000)return null
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(raw,0,raw.size,bounds)
                if(bounds.outWidth<=0||bounds.outHeight<=0)return null
                var sample=1
                while(bounds.outWidth/sample>460||bounds.outHeight/sample>680)sample*=2
                val opts=BitmapFactory.Options().apply{
                    inSampleSize=sample
                    inPreferredConfig=Bitmap.Config.RGB_565
                }
                val bm=BitmapFactory.decodeByteArray(raw,0,raw.size,opts)?:return null
                val resized=try{
                    val out=ByteArrayOutputStream()
                    bm.compress(Bitmap.CompressFormat.JPEG,70,out)
                    out.toByteArray()
                }finally{bm.recycle()}
                if(resized.isEmpty()||resized.size>350000)return null
                cache.writeBytes(resized)
                resized
            }
            return WebResourceResponse("image/jpeg",null,ByteArrayInputStream(bytes))
        }catch(e:Exception){
            Log.w("ZAHRUS-TV","Poster not available",e)
            return null
        }finally{slots.release()}
    }
}