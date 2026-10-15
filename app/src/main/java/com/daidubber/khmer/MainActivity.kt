package com.daidubber.khmer

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.*
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
 private var tts:TextToSpeech?=null
 private lateinit var status:TextView
 private lateinit var list:LinearLayout
 private val items=mutableListOf<String>()
 private var video:Uri?=null
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val scroll=ScrollView(this).apply { setBackgroundColor(Color.rgb(15,17,26)) }
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,36,24,24) }
  scroll.addView(root);setContentView(scroll)
  fun label(s:String,sz:Float):TextView { val v=TextView(this).apply { text=s; textSize=sz; setTextColor(Color.WHITE); setPadding(4,12,4,12) };root.addView(v);return v }
  fun button(s:String,action:()->Unit){ root.addView(Button(this).apply{text=s;isAllCaps=false;setOnClickListener{action()}}) }
  label("DAI Dubber Khmer",26f)
  label("Android starter • Khmer TTS preview",15f)
  status=label("Import SRT to begin",14f)
  button("Upload Video"){pick(10,"video/*")}
  button("Import SRT"){pick(11,"*/*")}
  button("Preview First Subtitle"){if(items.isNotEmpty()) speak(items.first()) else status.text="Import SRT first"}
  button("Export MP4 (not available yet)"){status.text="Export MP4 is not implemented in this starter"}
  list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(list)
  tts=TextToSpeech(this,this)
 }
 private fun pick(code:Int,type:String){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);setType(type)},code)}
 @Deprecated("Deprecated in Android API")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(resultCode!=RESULT_OK)return
  val uri=data?.data?:return
  if(requestCode==10){video=uri;status.text="Video chosen";return}
  if(requestCode==11){
   try{
    val src=contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()}
    items.clear()
    val time=Regex("""\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[,.]\d{3}""")
    for(block in src.replace("\r","").split(Regex("""\n\s*\n"""))){
     val lines=block.lines();val i=lines.indexOfFirst{time.containsMatchIn(it)}
     if(i>=0)items.add(lines.drop(i+1).joinToString(" ").trim())
    }
    list.removeAllViews()
    items.forEachIndexed{index,s->
     val edit=EditText(this).apply{setText(s);setTextColor(Color.WHITE)}
     list.addView(edit)
     list.addView(Button(this).apply{text="Speak #${index+1}";setOnClickListener{speak(edit.text.toString())}})
    }
    status.text="Imported ${items.size} subtitles"
   }catch(e:Exception){status.text="Import failed: ${e.message}"}
  }
 }
 private fun speak(s:String){
  val engine=tts?:return
  val voices=engine.voices?.filter{it.locale.language=="km"}?:emptyList()
  if(voices.isEmpty()){status.text="Khmer TTS voice not installed";return}
  engine.voice=voices.first()
  engine.speak(s,TextToSpeech.QUEUE_FLUSH,null,"preview")
 }
 override fun onInit(code:Int){
  if(code!=TextToSpeech.SUCCESS)return
  tts?.setLanguage(Locale.forLanguageTag("km-KH"))
 }
 override fun onDestroy(){tts?.shutdown();super.onDestroy()}
}