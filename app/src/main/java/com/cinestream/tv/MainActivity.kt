package com.cinestream.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebSettings
import android.webkit.RenderProcessGoneDetail
import android.util.Log
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity:Activity(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val handler=Handler(Looper.getMainLooper())
    private val interval=6L*60*60*1000
    private val prefs by lazy{getSharedPreferences("cs_prefs",MODE_PRIVATE)}
    private lateinit var root:FrameLayout
    private lateinit var web:WebView
    private lateinit var db:CatalogDatabase
    private val items=linkedMapOf<String,MediaEntry>()
    private var catalogLoaded=false
    private var playerHolder:FrameLayout?=null
    private var player:ExoPlayer?=null
    private var playerView:PlayerView?=null
    private var playingId:String?=null
    private var scanning=false
    private var pageReady=false
    private val intervalRunner=object:Runnable{
        override fun run(){
            // Manual scan only; do not start expensive network activity on launch.
            handler.postDelayed(this,interval)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        root=FrameLayout(this)
        setContentView(root)
        val loader=WebViewAssetLoader.Builder()
            .addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(this)).build()
        web=WebView(this).apply{
            settings.javaScriptEnabled=true
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.javaScriptCanOpenWindowsAutomatically=false
            webChromeClient=WebChromeClient()
            webViewClient=object:WebViewClientCompat(){
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?{
                    return loader.shouldInterceptRequest(request.url)
                }
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                    return !(request.url.scheme=="https"&&request.url.host=="appassets.androidplatform.net" &&
                        request.url.path?.startsWith("/assets/")==true)
                }
                override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean{
                    Log.e("ZAHRUS-TV","WebView renderer exited unexpectedly")
                    showRecovery("Android WebView stopped. Please update Android System WebView.")
                    return true
                }
                override fun onPageFinished(view:WebView,url:String){
                    if(url.startsWith("https://appassets.androidplatform.net/assets/")){
                        pageReady=true
                        send("status","Loading saved videos...")
                        scope.launch{
                            try{
                                val saved=withContext(Dispatchers.IO){
                                    db=CatalogDatabase(this@MainActivity)
                                    db.load()
                                }
                                saved.forEach{items[it.id]=it}
                                catalogLoaded=true
                                send("catalog",catalogJSON())
                                send("status","Loaded "+items.size+" files. Use Scan to check the ISP server.")
                            }catch(e:Exception){
                                Log.e("ZAHRUS-TV","Database loading failed",e)
                                send("status","Saved catalog could not be opened. No data was deleted.")
                            }
                        }
                    }
                }
            }
            addJavascriptInterface(Bridge(),"NativeTV")
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
            isFocusableInTouchMode=true
            requestFocus()
        }
        root.addView(web,FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
    }
    private fun showRecovery(message:String){
        runOnUiThread{
            if(!::root.isInitialized)root=FrameLayout(this)
            root.removeAllViews()
            val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(42,42,42,42);setBackgroundColor(Color.rgb(15,17,28))}
            layout.addView(TextView(this).apply{text="ZAHRUS TV · Recovery";textSize=27f;setTextColor(Color.WHITE)})
            layout.addView(TextView(this).apply{text=message+"\nExisting data is preserved.";textSize=18f;setTextColor(Color.LTGRAY)})
            layout.addView(Button(this).apply{text="Retry";setOnClickListener{recreate()}})
            root.addView(layout,FrameLayout.LayoutParams(-1,-1));setContentView(root)
        }
    }
    private fun send(type:String,value:String){
        runOnUiThread{
            if(pageReady){
                val js="window.fromNative("+JSONObject.quote(type)+","+JSONObject.quote(value)+")"
                web.evaluateJavascript(js,null)
            }
        }
    }
    private fun catalogJSON():String{
        val a=JSONArray()
        items.values.sortedByDescending{it.modified}.take(1800).forEach{v->
            a.put(JSONObject().apply{
                put("id",v.id);put("title",v.title);put("url",v.url)
                put("category",v.category);put("kind",v.kind);put("modified",v.modified)
                put("size",v.size);put("year",v.year?:"")
                val validPoster=v.poster?.takeIf{u->Sources.defaults.any{CatalogScanner.allowed(u,it.url)} && Regex("(?i)\\.(jpe?g|png|webp)$").containsMatchIn(u)}
                put("poster",validPoster?:"")
                put("season",v.season?:JSONObject.NULL);put("episode",v.episode?:JSONObject.NULL)
                put("favorite",prefs.getBoolean("fav_"+v.id,false))
                put("position",prefs.getLong("resume_"+v.id,0L))
            })
        }
        return a.toString()
    }
    inner class Bridge{
        @JavascriptInterface fun ready()=runOnUiThread{send("catalog",catalogJSON())}
        @JavascriptInterface fun refresh()=runOnUiThread{startScan()}
        @JavascriptInterface fun play(id:String)=runOnUiThread{items[id]?.let{openPlayer(it)}}
        @JavascriptInterface fun favorite(id:String,value:Boolean)=runOnUiThread{
            if(items.containsKey(id)){
                prefs.edit().putBoolean("fav_"+id,value).apply()
                send("catalog",catalogJSON())
            }
        }
        @JavascriptInterface fun external(id:String)=runOnUiThread{
            val item=items[id]?:return@runOnUiThread
            if(Sources.defaults.none{CatalogScanner.allowed(item.url,it.url)})return@runOnUiThread
            try{
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.url),"video/*"))
            }catch (_:Exception){Toast.makeText(this@MainActivity,"No external video player installed",Toast.LENGTH_LONG).show()}
        }
    }
    private fun pending(key:String):List<String> = try {
        val a=JSONArray(prefs.getString("queue_"+key,"[]"))
        (0 until a.length()).map{a.getString(it)}
    }catch (_:Exception){emptyList()}
    private fun startScan(){
        if(scanning){send("status","Already scanning. Please wait.");return}
        if(!catalogLoaded){send("status","Saved catalog is loading. Try Scan again shortly.");return}
        scanning=true
        send("status","Scanning media directories (Movies and Series only)...")
        scope.launch {
            var reached=0;var discovered=0
            for(source in Sources.defaults){
                val key=source.label.replace(Regex("[^A-Za-z0-9]"),"_")
                try{
                    val result=CatalogScanner.scan(source,pending(key),24)
                    result.items.forEach{items[it.id]=it}
                    withContext(Dispatchers.IO){db.save(result.items)}
                    discovered+=result.items.size
                    if(result.directories>0)reached++
                    prefs.edit().putString("queue_"+key,JSONArray(result.remaining).toString()).apply()
                    send("status",source.label+": "+result.directories+" dirs, "+result.items.size+
                        " videos; "+result.remaining.size+" folders left in queue")
                }catch(e:Exception){send("status",source.label+": "+(e.message?:"Connection failed"))}
            }
            if(reached>0)prefs.edit().putLong("sync",System.currentTimeMillis()).apply()
            scanning=false
            send("catalog",catalogJSON())
            send("complete","Scan completed. "+items.size+" cached videos; "+
                reached+"/"+Sources.defaults.size+" sources reached. Refresh after 6 hours while open.")
        }
    }
    private fun openPlayer(item:MediaEntry){
        if(Sources.defaults.none{CatalogScanner.allowed(item.url,it.url)}){
            Toast.makeText(this,"Invalid video source",Toast.LENGTH_LONG).show();return
        }
        closePlayer()
        val exo=ExoPlayer.Builder(this).build()
        val playerUi=PlayerView(this).apply{player=exo;useController=true;isFocusable=true}
        exo.addListener(object:Player.Listener{
            override fun onPlayerError(error:PlaybackException){
                Toast.makeText(this@MainActivity,"Playback error: "+error.errorCodeName+
                    ". Try external player.",Toast.LENGTH_LONG).show()
            }
        })
        player=exo;playerView=playerUi;playingId=item.id
        val holder=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        holder.addView(playerUi,FrameLayout.LayoutParams(-1,-1))
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val exit=Button(this).apply{text="Exit Fullscreen";setOnClickListener{closePlayer();send("catalog",catalogJSON())}}
        controls.addView(exit)
        holder.addView(controls,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        playerHolder=holder
        root.addView(holder,FrameLayout.LayoutParams(-1,-1))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        playerUi.requestFocus()
        exo.setMediaItem(ExoMediaItem.fromUri(item.url));exo.prepare()
        val pos=prefs.getLong("resume_"+item.id,0L)
        if(pos>0)exo.seekTo(pos)
        exo.playWhenReady=true
    }
    private fun savePosition(){
        val id=playingId?:return
        val exo=player?:return
        prefs.edit().putLong("resume_"+id,exo.currentPosition.coerceAtLeast(0L)).apply()
    }
    private fun closePlayer(){
        savePosition();player?.release();player=null
        playerHolder?.let{root.removeView(it)};playerHolder=null;playerView=null;playingId=null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_VISIBLE
        if(::web.isInitialized)web.requestFocus()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed(){
        if(player!=null){closePlayer();send("catalog",catalogJSON())}
        else if(::web.isInitialized)web.evaluateJavascript("window.tvBack()",null)
    }
    override fun onResume(){
        super.onResume()
        handler.removeCallbacks(intervalRunner)
        handler.postDelayed(intervalRunner,interval)
    }
    override fun onPause(){
        savePosition();player?.pause()
        handler.removeCallbacks(intervalRunner)
        super.onPause()
    }
    override fun onDestroy(){
        handler.removeCallbacks(intervalRunner)
        if(::root.isInitialized)closePlayer()
        if(::web.isInitialized){runCatching{web.removeJavascriptInterface("NativeTV")};runCatching{web.destroy()}}
        if(::db.isInitialized)runCatching{db.close()}
        scope.cancel()
        super.onDestroy()
    }
}