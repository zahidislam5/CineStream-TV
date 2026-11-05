package com.cinestream.tv

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * One-time preloaded media catalog installation for ZAHRUS TV.
 * Normal launches never crawl the ISP server.
 * The catalog stays inside app-private SQLite storage.
 */
object PreloadedCatalog {
    const val ASSET = "catalog_seed.db.gz"
    const val VERSION = "2026-11-05-exporter-1.0.0"
    const val SHA256 = "526a9fad216cca49ecec9d61568491027347ffe38932d68432a66924ead77339"
    private const val MIN_ITEMS = 200_000

    fun available(context:Context):Boolean =
        context.assets.list("")?.contains(ASSET) == true

    private fun sha256(bytes:ByteArray):String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun decompress(context:Context,destination:File){
        val digest=MessageDigest.getInstance("SHA-256")
        try{
            context.assets.open(ASSET).use { input ->
                DigestInputStream(input,digest).use { stream ->
                    GZIPInputStream(stream,64*1024).use { gzip ->
                        FileOutputStream(destination).buffered(128*1024).use { out ->
                            gzip.copyTo(out,128*1024)
                        }
                    }
                }
            }
            if(sha256(digest.digest())!=SHA256){
                throw IllegalStateException("Bundled catalog SHA-256 mismatch")
            }
            SQLiteDatabase.openDatabase(destination.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                val count=DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM videos",null)
                val version=DatabaseUtils.longForQuery(db,"PRAGMA user_version",null)
                if(count<MIN_ITEMS || version!=4L){
                    throw IllegalStateException("Invalid catalog schema or row count")
                }
            }
        }catch(e:Exception){
            destination.delete()
            throw e
        }
    }

    fun installForFreshApp(context:Context):Boolean {
        if(!available(context))return false
        val target=context.getDatabasePath("cinestream.db")
        if(target.exists())return false
        target.parentFile?.mkdirs()
        val temp=File(target.parentFile,"catalog-install.tmp")
        temp.delete()
        decompress(context,temp)
        if(!temp.renameTo(target)){
            temp.delete()
            throw IllegalStateException("Failed to install preloaded catalog")
        }
        Log.i("ZAHRUS-TV","Preloaded catalog installed into private database")
        return true
    }

    fun importForExistingApp(context:Context,store:LibraryStore):Boolean {
        if(!available(context)||store.hasSeed(VERSION))return false
        val temp=File(context.filesDir,"catalog-merge.tmp")
        temp.delete()
        try{
            decompress(context,temp)
            store.mergeSeed(temp,VERSION)
            Log.i("ZAHRUS-TV","Preloaded catalog merged; user data preserved")
            return true
        }finally{
            temp.delete()
        }
    }
}