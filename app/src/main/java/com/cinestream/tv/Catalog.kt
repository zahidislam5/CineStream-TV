package com.cinestream.tv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque

data class Source(val label:String, val url:String, val kind:String)
object Sources {
    // Movie/Series category URLs extracted from the user's DhakaFlix HTML samples.
    // Deliberately exclude Games, Software, Apps, ISOs and installers.
    val defaults=listOf(
        Source("English Movies","http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/","Movie"),
        Source("English 1080p","http://172.16.50.14/DHAKA-FLIX-14/English%20Movies%20%281080p%29/","Movie"),
        Source("Hindi Movies","http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/","Movie"),
        Source("South Indian","http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/South%20Movies/","Movie"),
        Source("Hindi Dubbed","http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/","Movie"),
        Source("Bangla Movies","http://172.16.50.7/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/","Movie"),
        Source("Animation","http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies/","Movie"),
        Source("Animation 1080p","http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies%20%281080p%29/","Movie"),
        Source("Foreign Movies","http://172.16.50.7/DHAKA-FLIX-7/Foreign%20Language%20Movies/","Movie"),
        Source("IMDb Top 250","http://172.16.50.14/DHAKA-FLIX-14/IMDb%20Top-250%20Movies/","Movie"),
        Source("3D Movies","http://172.16.50.7/DHAKA-FLIX-7/3D%20Movies/","Movie"),
        Source("TV & Web Series","http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/","Series"),
        Source("Korean Series","http://172.16.50.14/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/","Series"),
        Source("Anime Series","http://172.16.50.10/DHAKA-FLIX-10/Anime%20%26%20Cartoon%20TV%20Series/","Series"),
        Source("Documentaries","http://172.16.50.10/DHAKA-FLIX-10/Documentary/","Movie")
    )
}
data class MediaEntry(
    val id:String,val title:String,val url:String,val category:String,val kind:String,
    val modified:String,val size:String,val poster:String?=null,
    val season:Int?=null,val episode:Int?=null,val year:String?=null
)
data class FolderEntry(val name:String,val url:String,val modified:String,val size:String,val folder:Boolean)
data class ScanResult(val items:List<MediaEntry>,val remaining:List<String>,val directories:Int,val errors:Int)

object CatalogScanner {
    private val hosts=setOf("172.16.50.7","172.16.50.10","172.16.50.12","172.16.50.14")
    private val videos=Regex("(?i)\\.(mkv|mp4|m4v|webm|avi|mov|ts)$")
    private val images=Regex("(?i)\\.(jpg|jpeg|png|webp)$")
    private val ep=Regex("(?i)S(\\d{1,2})[ ._-]*E(\\d{1,3})")
    private val epAlt=Regex("(?i)(?:episode|ep)[ ._-]*(\\d{1,3})")
    private val seasonPat=Regex("(?i)season[ ._-]*(\\d{1,3})")
    private val year=Regex("\\b(?:19|20)\\d{2}\\b")
    private val excluded=Regex("(?i)(?:^|[\\s_-])(games?|softwares?|applications?|installers?)(?:$|[\\s_-])")
    fun allowed(url:String,root:String):Boolean=try{
        val u=URI(url).normalize();val r=URI(root).normalize()
        u.scheme=="http" && u.host==r.host && u.host in hosts && u.port==r.port &&
        u.userInfo==null && u.fragment==null && u.query==null && u.rawPath.startsWith(r.rawPath) &&
        !u.rawPath.contains("%2e",true) && !u.rawPath.contains("%2f",true) && !u.rawPath.contains("%5c",true)
    }catch (_:Exception){false}
    fun list(html:String,base:String):List<FolderEntry>{
        val document=Jsoup.parse(html,base)
        return document.select("#fallback tr").mapNotNull { row ->
            val cells=row.select("td")
            if(cells.size<4)return@mapNotNull null
            val a=cells[1].selectFirst("a[href]")?:return@mapNotNull null
            val name=a.text().trim();val url=a.absUrl("href")
            if(name.isEmpty()||name.equals("Parent Directory",true)||url.isEmpty())return@mapNotNull null
            val folder=cells[0].selectFirst("img")?.attr("alt")=="folder"||url.endsWith("/")
            FolderEntry(name,url,cells[2].text().trim(),cells[3].text().trim(),folder)
        }
    }
    private fun fetch(url:String):String{
        val c=URL(url).openConnection() as HttpURLConnection
        c.connectTimeout=6000;c.readTimeout=9000;c.instanceFollowRedirects=false
        c.setRequestProperty("User-Agent","CineStream-TV/0.4")
        return try{
            if(c.responseCode!=200)throw Exception("HTTP "+c.responseCode)
            if(c.contentLengthLong>4_000_000L)throw Exception("HTML too large")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result=reader.readText()
                if(result.length>4_000_000)throw Exception("HTML too large")
                result
            }
        }finally{c.disconnect()}
    }
    private fun id(s:String):String=MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
       .take(12).joinToString(""){"%02x".format(it)}
    private fun path(url:String,root:String):List<String>{
        val s=URI(url).rawPath.removePrefix(URI(root).rawPath)
        return s.split("/").filter { it.isNotBlank() }.map {
            try{java.net.URLDecoder.decode(it.replace("+","%2B"),"UTF-8")}catch (_:Exception){it}
        }
    }
    private fun tidy(value:String):String=value.substringBeforeLast(".",value)
        .replace("_"," ").replace(Regex("(?i)\\s+(?:2160p|1080p|720p|480p|WEBRip|WEB-DL|BluRay|x264|x265|HEVC)\\b.*$"),"")
        .trim().ifBlank{value}
    private fun title(folders:List<String>,file:String,kind:String):String{
        val meaningful=folders.asReversed().firstOrNull {
            !it.matches(Regex("\\(?\\d{4}\\)?")) &&
            !it.startsWith("Season ",true) &&
            !it.startsWith("TV Series ♥") && !it.startsWith("TV Series ♦") &&
            !it.startsWith("TV Series ★") &&
            !it.equals("TV-WEB-Series",true)
        }
        return tidy(meaningful?:file)
    }
    suspend fun scan(source:Source,pending:List<String>,maxDirectories:Int=24):ScanResult=withContext(Dispatchers.IO){
        val queue=ArrayDeque<String>()
        (if(pending.isEmpty())listOf(source.url) else pending).forEach{queue.addLast(it)}
        val seen=mutableSetOf<String>();val output=mutableListOf<MediaEntry>()
        var pages=0;var errors=0
        while(queue.isNotEmpty()&&pages<maxDirectories){
            val url=queue.removeLast()
            if(!allowed(url,source.url)||!seen.add(url))continue
            val entries=try{list(fetch(url),url)}catch (_:Exception){
                errors++;queue.addLast(url);break
            }
            pages++
            val folders=path(url,source.url)
            val poster=entries.firstOrNull{!it.folder&&images.containsMatchIn(it.name)&&
              (it.name.contains("poster",true)||it.name.contains("cover",true))}
              ?:entries.firstOrNull{!it.folder&&images.containsMatchIn(it.name)}
            entries.filter{it.folder&&!excluded.containsMatchIn(it.name)&&allowed(it.url,source.url)}
                .sortedBy{it.modified}.forEach{queue.addLast(it.url)}
            for(entry in entries){
                if(entry.folder||!videos.containsMatchIn(entry.name)||!allowed(entry.url,source.url))continue
                val matched=ep.find(entry.name)
                val season=matched?.groupValues?.get(1)?.toIntOrNull()
                  ?: folders.asReversed().firstNotNullOfOrNull{seasonPat.find(it)?.groupValues?.get(1)?.toIntOrNull()}
                val episode=matched?.groupValues?.get(2)?.toIntOrNull()
                  ?:epAlt.find(entry.name)?.groupValues?.get(1)?.toIntOrNull()
                val name=title(folders,entry.name,source.kind)
                output.add(MediaEntry(id(entry.url),name,entry.url,source.label,source.kind,
                    entry.modified,entry.size,poster?.url,season,episode,year.find(name)?.value))
            }
        }
        ScanResult(output.distinctBy{it.id},queue.toList(),pages,errors)
    }
}
class CatalogDatabase(ctx:Context):SQLiteOpenHelper(ctx,"cinestream.db",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS videos(id TEXT PRIMARY KEY,title TEXT,url TEXT,category TEXT,kind TEXT,modified TEXT,size TEXT,poster TEXT,season INTEGER,episode INTEGER,year TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_videos_modified ON videos(modified)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    fun load():List<MediaEntry>{
        val out=mutableListOf<MediaEntry>()
        readableDatabase.rawQuery("SELECT id,title,url,category,kind,modified,size,poster,season,episode,year FROM videos ORDER BY modified DESC",null).use{c->
            while(c.moveToNext())out.add(MediaEntry(
                c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),
                c.getString(5)?:"",c.getString(6)?:"",c.getString(7),
                if(c.isNull(8))null else c.getInt(8),
                if(c.isNull(9))null else c.getInt(9),c.getString(10)))
        }
        return out
    }
    fun save(items:List<MediaEntry>){
        val db=writableDatabase;db.beginTransaction()
        try{
            val query="INSERT OR REPLACE INTO videos(id,title,url,category,kind,modified,size,poster,season,episode,year) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
            val statement=db.compileStatement(query)
            try{for(v in items){
                statement.clearBindings()
                listOf(v.id,v.title,v.url,v.category,v.kind,v.modified,v.size).forEachIndexed { i,s->statement.bindString(i+1,s) }
                if(v.poster==null)statement.bindNull(8) else statement.bindString(8,v.poster)
                if(v.season==null)statement.bindNull(9) else statement.bindLong(9,v.season.toLong())
                if(v.episode==null)statement.bindNull(10) else statement.bindLong(10,v.episode.toLong())
                if(v.year==null)statement.bindNull(11) else statement.bindString(11,v.year)
                statement.executeInsert()
            }}finally{statement.close()}
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
}