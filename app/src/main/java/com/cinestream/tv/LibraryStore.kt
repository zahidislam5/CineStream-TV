package com.cinestream.tv

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class BrowseResult(val total:Int,val results:List<MediaEntry>)
data class CategoryCount(val name:String,val count:Int)
data class ScanStats(val pending:Int,val done:Int,val failed:Int,val files:Int)

class LibraryStore(ctx:Context):SQLiteOpenHelper(ctx,"cinestream.db",null,4){
    private val fields="id,title,url,category,kind,modified,size,poster,season,episode,year,genre,quality"

    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS videos(id TEXT PRIMARY KEY,title TEXT,url TEXT,category TEXT,kind TEXT,modified TEXT,size TEXT,poster TEXT,season INTEGER,episode INTEGER,year TEXT,genre TEXT,quality TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_videos_modified ON videos(modified)")
        makeTables(db)
    }
    private fun makeTables(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS crawl_queue(source TEXT NOT NULL,url TEXT NOT NULL,state INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(source,url))")
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_crawl_pending ON crawl_queue(source,state)")
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites(id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE IF NOT EXISTS seed_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_video_category ON videos(kind,category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_video_title ON videos(title COLLATE NOCASE)")
    }
    override fun onUpgrade(db:SQLiteDatabase,from:Int,to:Int){
        if(from<2){
            val columns=mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(videos)",null).use{c->
                val i=c.getColumnIndex("name")
                if(i>=0)while(c.moveToNext())columns.add(c.getString(i))
            }
            if("genre" !in columns)db.execSQL("ALTER TABLE videos ADD COLUMN genre TEXT")
            if("quality" !in columns)db.execSQL("ALTER TABLE videos ADD COLUMN quality TEXT")
        }
        if(from<3)makeTables(db)
        if(from<4){
            db.execSQL("CREATE TABLE IF NOT EXISTS seed_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("DELETE FROM videos WHERE category=\'Documentaries\'")
            db.execSQL("DELETE FROM crawl_queue WHERE source=\'Documentaries\'")
        }
    }
    fun hasSeed(version:String):Boolean {
        return readableDatabase.rawQuery(
            "SELECT 1 FROM seed_meta WHERE key='seed_version' AND value=? LIMIT 1",
            arrayOf(version)).use { it.moveToFirst() }
    }

    /** Merge only missing video URLs: user-scanned newer data takes precedence. */
    fun mergeSeed(seed:java.io.File,version:String){
        if(hasSeed(version))return
        val db=writableDatabase
        db.execSQL("ATTACH DATABASE ? AS preloaded",arrayOf(seed.absolutePath))
        try{
            db.beginTransaction()
            try{
                db.execSQL("INSERT OR IGNORE INTO videos("+fields+") SELECT "+
                    fields+" FROM preloaded.videos WHERE kind IN ('Movie','Series') "+
                    "AND category!='Documentaries'")
                db.execSQL("INSERT OR IGNORE INTO crawl_queue(source,url,state) "+
                    "SELECT source,url,state FROM preloaded.crawl_queue "+
                    "WHERE source!='Documentaries'")
                db.execSQL("DELETE FROM videos WHERE category='Documentaries'")
                db.execSQL("DELETE FROM crawl_queue WHERE source='Documentaries'")
                db.execSQL("INSERT OR REPLACE INTO seed_meta(key,value) VALUES('seed_version',?)",
                    arrayOf(version))
                db.setTransactionSuccessful()
            }finally{db.endTransaction()}
        }finally{db.execSQL("DETACH DATABASE preloaded")}
    }

    private fun long(sql:String,args:Array<String> = emptyArray()):Long =
        DatabaseUtils.longForQuery(readableDatabase,sql,args)

    fun importFavorites(prefs:android.content.SharedPreferences){
        val db=writableDatabase
        db.beginTransaction()
        try{
            prefs.all.filter{it.key.startsWith("fav_") && it.value==true}.forEach{
                db.execSQL("INSERT OR IGNORE INTO favorites(id) VALUES(?)",arrayOf(it.key.removePrefix("fav_")))
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun setFavorite(id:String,value:Boolean){
        if(value)writableDatabase.execSQL("INSERT OR IGNORE INTO favorites(id) VALUES(?)",arrayOf(id))
        else writableDatabase.delete("favorites","id=?",arrayOf(id))
    }
    fun isFavorite(id:String):Boolean =
        long("SELECT count(*) FROM favorites WHERE id=?",arrayOf(id))>0

    private fun row(c:Cursor):MediaEntry{
        val index=fun(n:String)=c.getColumnIndexOrThrow(n)
        fun str(n:String):String?{val i=index(n);return if(c.isNull(i))null else c.getString(i)}
        fun int(n:String):Int?{val i=index(n);return if(c.isNull(i))null else c.getInt(i)}
        return MediaEntry(
            str("id")?:"",str("title")?:"",str("url")?:"",str("category")?:"",str("kind")?:"",
            str("modified")?:"",str("size")?:"",str("poster"),int("season"),int("episode"),str("year"),
            str("genre"),str("quality")
        )
    }
    fun get(id:String):MediaEntry?=readableDatabase.rawQuery(
        "SELECT "+fields+" FROM videos WHERE id=? LIMIT 1",arrayOf(id)).use { if(it.moveToFirst())row(it) else null }

    fun totalFiles():Int=long("SELECT count(*) FROM videos").toInt()
    fun scanStats():ScanStats=ScanStats(
        long("SELECT count(*) FROM crawl_queue WHERE state=0").toInt(),
        long("SELECT count(*) FROM crawl_queue WHERE state=1").toInt(),
        long("SELECT count(*) FROM crawl_queue WHERE state=2").toInt(),
        totalFiles()
    )
    fun categories(tab:String):List<CategoryCount>{
        val where=when(tab){"Movies"->"WHERE kind='Movie'";"Series"->"WHERE kind='Series'";else->""}
        val result=mutableListOf<CategoryCount>()
        readableDatabase.rawQuery("SELECT category,COUNT(DISTINCT title) FROM videos "+where+
            " GROUP BY category ORDER BY category COLLATE NOCASE",null).use{c->
            while(c.moveToNext())result.add(CategoryCount(c.getString(0),c.getInt(1)))
        }
        return result
    }
    fun years():List<String>{
        val result=mutableListOf<String>()
        readableDatabase.rawQuery("SELECT DISTINCT year FROM videos WHERE year GLOB '[12][0-9][0-9][0-9]' ORDER BY year DESC LIMIT 80",null).use{c->
            while(c.moveToNext())result.add(c.getString(0))
        }
        return result
    }

    fun browse(tab:String,category:String,year:String,search:String,sort:String,offset:Int,limit:Int=48):BrowseResult{
        val where=mutableListOf<String>()
        val args=mutableListOf<String>()
        when(tab){
            "Movies"->where.add("v.kind='Movie'")
            "Series"->where.add("v.kind='Series'")
            "Favorites"->where.add("v.id IN (SELECT id FROM favorites)")
        }
        if(category.isNotBlank()){where.add("v.category=?");args.add(category)}
        if(year.isNotBlank()){where.add("v.year=?");args.add(year)}
        val tokens=search.trim().split(Regex("\\s+")).filter{it.isNotBlank()}.take(6)
        for(token in tokens){
            where.add("(v.title LIKE ? OR v.category LIKE ? OR v.year LIKE ? OR v.genre LIKE ? OR v.url LIKE ?)")
            val like="%"+token.take(65)+"%"
            repeat(5){args.add(like)}
        }
        val predicate=if(where.isEmpty())"" else " WHERE "+where.joinToString(" AND ")
        val distinct="SELECT MAX(v.rowid) AS selected_row FROM videos v"+predicate+
            " GROUP BY v.kind,v.category,v.title"
        val sqlCount="SELECT count(*) FROM ("+distinct+")"
        val total=long(sqlCount,args.toTypedArray()).toInt()
        val order=when(sort){
            "az"->"title COLLATE NOCASE ASC"
            "za"->"title COLLATE NOCASE DESC"
            "year"->"year DESC, title COLLATE NOCASE"
            "old"->"modified ASC"
            else->"modified DESC"
        }
        val sql="SELECT "+fields+" FROM videos WHERE rowid IN ("+distinct+
            ") ORDER BY "+order+" LIMIT ? OFFSET ?"
        val allArgs=(args+listOf(limit.coerceIn(1,60).toString(),offset.coerceAtLeast(0).toString())).toTypedArray()
        val results=mutableListOf<MediaEntry>()
        readableDatabase.rawQuery(sql,allArgs).use{c->while(c.moveToNext())results.add(row(c))}
        return BrowseResult(total,results)
    }
    fun episodes(item:MediaEntry,offset:Int=0):BrowseResult{
        val args=arrayOf(item.kind,item.category,item.title)
        val total=long("SELECT count(*) FROM videos WHERE kind=? AND category=? AND title=?",args).toInt()
        val result=mutableListOf<MediaEntry>()
        readableDatabase.rawQuery("SELECT "+fields+
            " FROM videos WHERE kind=? AND category=? AND title=? ORDER BY season,episode,url LIMIT 100 OFFSET ?",
            args+arrayOf(offset.coerceAtLeast(0).toString())).use{c->while(c.moveToNext())result.add(row(c))}
        return BrowseResult(total,result)
    }

    /** First full scan seeds all allowed roots. Subsequent scans resume or retry failed directories. */
    fun prepareScan():String{
        val db=writableDatabase
        db.beginTransaction()
        try{
            val pending=DatabaseUtils.longForQuery(db,"SELECT count(*) FROM crawl_queue WHERE state=0",null)
            if(pending>0){db.setTransactionSuccessful();return "Resuming pending directories"}
            // A deliberate new scan rechecks completed directories for fresh videos.
            // If interrupted, pending directories remain and the next run resumes.
            db.execSQL("UPDATE crawl_queue SET state=0 WHERE source!='Documentaries'")
            db.execSQL("DELETE FROM crawl_queue WHERE source='Documentaries'")
            for(source in Sources.defaults){
                db.execSQL("INSERT OR IGNORE INTO crawl_queue(source,url,state) VALUES(?,?,0)",
                    arrayOf(source.label,source.url))
            }
            db.setTransactionSuccessful()
            return "Starting a full library scan"
        }finally{db.endTransaction()}
    }
    fun nextFolder(source:Source):String?=readableDatabase.rawQuery(
        "SELECT url FROM crawl_queue WHERE source=? AND state=0 ORDER BY rowid LIMIT 1",
        arrayOf(source.label)).use { if(it.moveToFirst())it.getString(0) else null }

    /** Upsert the discovered video files and folder queue in a single transaction. */
    fun completeFolder(source:Source,url:String,children:List<String>,videos:List<MediaEntry>){
        val db=writableDatabase
        db.beginTransaction()
        try{
            val stmt=db.compileStatement("INSERT OR REPLACE INTO videos("+fields+
                ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")
            try{
                for(video in videos){
                    stmt.clearBindings()
                    val oldPoster=db.rawQuery("SELECT poster FROM videos WHERE id=?",arrayOf(video.id)).use{c->
                        if(c.moveToFirst())c.getString(0) else null
                    }
                    val values=listOf(video.id,video.title,video.url,video.category,video.kind,
                        video.modified,video.size,video.poster?:oldPoster,
                        video.season?.toString(),video.episode?.toString(),video.year,video.genre,video.quality)
                    values.forEachIndexed { i,v ->
                        if(v==null)stmt.bindNull(i+1)
                        else if(i==8||i==9)stmt.bindLong(i+1,v.toLong())
                        else stmt.bindString(i+1,v)
                    }
                    stmt.executeInsert()
                }
            }finally{stmt.close()}
            for(child in children.distinct()){
                if(CatalogScanner.allowed(child,source.url)){
                    db.execSQL("INSERT OR IGNORE INTO crawl_queue(source,url,state) VALUES(?,?,0)",
                        arrayOf(source.label,child))
                }
            }
            db.execSQL("UPDATE crawl_queue SET state=1 WHERE source=? AND url=?",
                arrayOf(source.label,url))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun failFolder(source:Source,url:String){
        writableDatabase.execSQL("UPDATE crawl_queue SET state=2 WHERE source=? AND url=?",
            arrayOf(source.label,url))
    }
}