'use strict';
// DubStudio KH Pro Mobile: local UI + native Android bridge. AI/API secrets stay on the server.
const $ = id => document.getElementById(id);
const state = { tab:'import', video:null, subtitles:[], audioReady:false, server:'', isBusy:false, ttsReady:false };
const pending = new Map();
let progressStarted=0, progressTimer=null, toastTimer=null, translateInProgress=false;
const hasNative = !!window.DubNative;
function native(action, data={}) {
  return new Promise((resolve, reject)=>{
    if (!hasNative) { reject(new Error('Run this interface in the Android app')); return; }
    if(pending.has(action)){reject(new Error('Action already pending'));return;}
    pending.set(action,{resolve,reject});
    try { window.DubNative.call(action,JSON.stringify(data)); }
    catch(e){pending.delete(action);reject(e);}
  });
}
window.__nativeResult = function(msg){
  const rec=pending.get(msg.action); if(rec){ pending.delete(msg.action); if(msg.ok) rec.resolve(msg.data||{}); else rec.reject(new Error(msg.error||'Unknown error')); }
  if(msg.action==='pickVideo' && msg.ok){state.video={name:msg.data.name,size:msg.data.size};state.audioReady=false;update();toast('✓ Video បានបញ្ចូលរួច');}
  if(msg.action==='pickSrt' && msg.ok){const parsed=parseSrt(msg.data.srt||'');if(!parsed.length){toast('មិនឃើញ Subtitle ក្នុងឯកសារ SRT');return;}state.subtitles=parsed;state.audioReady=false;changeTab('subtitles');toast('✓ បាន Import '+parsed.length+' Subtitle');}
  if((msg.action==='pickVideo'||msg.action==='pickSrt')&&!msg.ok) toast(msg.error||'File import failed');
};
window.__nativeProgress=function(msg){
  if(!state.isBusy || translateInProgress)return;
  $('progressTitle').textContent=({transcribe:'Generating subtitles',audio:'Generating Khmer voices',export:'Exporting MP4',ping:'Checking server',recap:'Creating recap',saveSrt:'Saving SRT'})[msg.action]||'Working…';
  const label=msg.stage==='processing'?'AI / Server កំពុងដំណើរការ…':msg.label;
  setProgress(msg.percent,label);
};
window.__ttsReady=function(msg){state.ttsReady=!!msg.available;};
function toast(text){const el=$('toast');el.textContent=text;el.classList.remove('hidden');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.add('hidden'),4300);}
function changeTab(name){if(state.isBusy)return;state.tab=name;document.querySelectorAll('.screen').forEach(x=>x.classList.toggle('hidden',x.id!=='screen-'+name));document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b.dataset.tab===name));let order={import:1,subtitles:2,voices:3,export:4,settings:4};$('stepFill').style.width=((order[name]||1)*25)+'%';$('stepText').textContent=name==='settings'?'Server settings':`ជំហាន ${order[name]} / 4 · `+({import:'Video',subtitles:'Subtitle',voices:'Voices',export:'Export'})[name];update();window.scrollTo(0,0);}
function setProgress(percent,label){$('progressText').textContent=label||'កំពុងដំណើរការ…';const known=Number.isFinite(percent)&&percent>=0;const bar=$('workFill');bar.parentNode.classList.toggle('known',known);bar.style.width=known?Math.max(0,Math.min(100,percent))+'%':'100%';$('progressPercent').textContent=known?`${Math.round(percent)}% completed`:'កំពុងដំណើរការ… (មិនមាន % ពី Server)';}
function startBusy(label){state.isBusy=true;progressStarted=Date.now();$('progressLayer').classList.remove('hidden');$('progressTitle').textContent=label;setProgress(-1,'សូមរក្សាកម្មវិធីឱ្យនៅបើករហូតដល់ការងារបញ្ចប់');clearInterval(progressTimer);progressTimer=setInterval(()=>{const s=Math.floor((Date.now()-progressStarted)/1000);$('progressTime').textContent=String(Math.floor(s/60)).padStart(2,'0')+':'+String(s%60).padStart(2,'0');},500);document.querySelectorAll('main button,nav button').forEach(x=>x.disabled=true);}
function stopBusy(){state.isBusy=false;translateInProgress=false;clearInterval(progressTimer);$('progressLayer').classList.add('hidden');document.querySelectorAll('main button,nav button').forEach(x=>x.disabled=false);}
async function job(title,callback){if(state.isBusy)return;startBusy(title);try{return await callback();}catch(e){toast('✕ '+(e?.message||'Operation failed'));return null;}finally{stopBusy();update();}}
function validTimes(){return state.subtitles.length>0 && state.subtitles.every(x=>x.text.trim()&&Number.isFinite(x.start)&&Number.isFinite(x.end)&&x.end>x.start&&x.start>=0&&x.end<=3600);}
function payload(lines=state.subtitles){return{ subtitles:lines.map(({start,end,text,voice})=>({start,end,text:text.trim(),voice:voice||'Female'}))};}
function fmt(seconds){let ms=Math.round(Math.max(0,seconds)*1000);const h=Math.floor(ms/3600000);ms%=3600000;const m=Math.floor(ms/60000);ms%=60000;const s=Math.floor(ms/1000);ms%=1000;return[h,m,s].map(x=>String(x).padStart(2,'0')).join(':')+','+String(ms).padStart(3,'0');}
function sec(clock){const match=clock.trim().match(/^(\d{2}):(\d{2}):(\d{2})[,.](\d{3})$/);if(!match)return NaN;return (+match[1])*3600+(+match[2])*60+(+match[3])+(+match[4])/1000;}
function parseSrt(raw){return String(raw).replace(/^\uFEFF/,'').replace(/\r/g,'').trim().split(/\n\s*\n/g).map(block=>{let lines=block.split('\n');let idx=lines.findIndex(x=>x.includes('-->'));if(idx<0)return null;const p=lines[idx].split(/\s*-->\s*/);if(p.length!==2)return null;const start=sec(p[0]),end=sec(p[1]);const text=lines.slice(idx+1).join('\n').trim();if(!text||!Number.isFinite(start)||!Number.isFinite(end)||end<=start||end>3600)return null;return{start,end,text,voice:'Female'};}).filter(Boolean);}
function toSrt(){return state.subtitles.map((x,i)=>`${i+1}\n${fmt(x.start)} --> ${fmt(x.end)}\n${x.text.trim()}\n`).join('\n');}
function update(){const v=state.video;
  $('videoName').textContent=v?v.name:'ចុចដើម្បីជ្រើសវីដេអូ';$('videoDetail').textContent=v?`${v.size>=0?(v.size/1048576).toFixed(1)+' MB':'Unknown size'} · Ready`:'MP4, MOV និង video formats ផ្សេងៗ · ដល់ 200 MB';
  $('totalLines').textContent=state.subtitles.length+' subtitles';$('listCount').textContent=state.subtitles.length+' lines';$('voiceCount').textContent=state.subtitles.length;
  $('voiceSplit').textContent=state.subtitles.filter(x=>x.voice==='Male').length+' / '+state.subtitles.filter(x=>x.voice==='Female').length;
  $('exportVideoLabel').textContent=v?v.name:'Not selected';$('exportAudioLabel').textContent=state.audioReady?'✓ WAV generated':'Not generated';$('exportSubsLabel').textContent=state.subtitles.length+' lines';
  $('openVideo').disabled=!v;$('startTranscribe').disabled=!v;$('translate').disabled=!validTimes();$('recap').disabled=!validTimes();$('preview').disabled=!validTimes();$('generateAudio').disabled=!validTimes();$('exportVideo').disabled=!(v&&state.audioReady);$('saveSrt').disabled=!validTimes();$('saveSrt2').disabled=!validTimes();
  if(state.tab==='subtitles')renderSubs();
}
function renderSubs(){const container=$('subtitleList');container.replaceChildren();$('emptySub').classList.toggle('hidden',state.subtitles.length>0);
  state.subtitles.forEach((line,i)=>{
    const card=document.createElement('article');card.className='subtitle-card';
    const top=document.createElement('div');top.className='subtitle-header';
    const index=document.createElement('span');index.className='subtitle-index';index.textContent='#'+String(i+1).padStart(2,'0');
    const time=document.createElement('span');time.className='subtitle-time';time.textContent=`${fmt(line.start)} → ${fmt(line.end)}`;
    const remove=document.createElement('button');remove.className='small-remove';remove.textContent='×';remove.setAttribute('aria-label','Remove subtitle');remove.onclick=()=>{state.subtitles.splice(i,1);state.audioReady=false;update();};
    top.append(index,time,remove);card.append(top);
    const txt=document.createElement('textarea');txt.rows=2;txt.value=line.text;txt.setAttribute('aria-label','Subtitle '+(i+1));txt.oninput=()=>{line.text=txt.value;state.audioReady=false;};card.append(txt);
    const opts=document.createElement('div');opts.className='subtitle-options';const label=document.createElement('label');label.textContent='AI Character Voice';const select=document.createElement('select');
    ['Female','Male'].forEach(voice=>{const opt=document.createElement('option');opt.value=voice;opt.textContent=voice==='Female'?'♀ Female':'♂ Male';select.append(opt);});select.value=line.voice;select.onchange=()=>{line.voice=select.value;state.audioReady=false;};opts.append(label,select);card.append(opts);
    const tg=document.createElement('div');tg.className='time-grid';
    for(const which of ['start','end']){const box=document.createElement('label');box.textContent=which==='start'?'Start (seconds)':'End (seconds)';const input=document.createElement('input');input.type='number';input.min='0';input.max='3600';input.step='0.001';input.value=String(line[which]);input.onchange=()=>{line[which]=Number(input.value);state.audioReady=false;time.textContent=`${fmt(line.start)} → ${fmt(line.end)}`;};box.append(input);tg.append(box);}card.append(tg);container.append(card);
  });
}
function requireLines(){if(!validTimes()){toast('សូមបញ្ចូល Subtitle និងកែ Timing ឱ្យត្រឹមត្រូវជាមុន');return false;}return true;}
async function checkServer(){await job('Testing server',async()=>{const status=await native('ping');$('serverStatus').className='server-status online';$('serverStatus').lastElementChild.textContent='✓ Server online · Connected';$('aiStatus').textContent=status.ai_configured?'Ready':'No key';$('ffmpegStatus').textContent=status.ffmpeg?'Ready':'Missing';$('ttsStatus').textContent=status.tts?'Ready':'Missing';toast('✓ ភ្ជាប់ Server ជោគជ័យ');});}
function hideSaved(){$('savedInfo').classList.add('hidden');}
function showSaved(s){$('savedInfo').classList.remove('hidden');$('savedInfo').textContent='✓ Saved to '+s;}
function bind(id,callback){$(id).addEventListener('click',callback);}
function init(){document.querySelectorAll('[data-tab]').forEach(b=>b.addEventListener('click',()=>changeTab(b.dataset.tab)));
  bind('chooseVideo',()=>window.DubNative?.call('pickVideo','{}'));
  bind('openVideo',()=>window.DubNative?.call('openVideo','{}'));
  bind('importSrt',()=>window.DubNative?.call('pickSrt','{}'));
  bind('goSubs',()=>changeTab('subtitles'));
  bind('startTranscribe',()=>job('Generating subtitles',async()=>{const result=await native('transcribe');if(!result.subtitles?.length)throw Error('Server returned no subtitles');state.subtitles=result.subtitles.map(x=>({...x,voice:x.voice||'Female'}));state.audioReady=false;changeTabAfterJob('subtitles');toast(`✓ Generate បាន ${state.subtitles.length} subtitles`);}));
  bind('addLine',()=>{const last=state.subtitles[state.subtitles.length-1];const start=last?last.end:0;state.subtitles.push({start,end:Math.min(3600,start+2),text:'',voice:'Female'});state.audioReady=false;renderSubs();});
  const doSrt=()=>{if(!requireLines())return;job('Saving SRT',async()=>{const result=await native('saveSrt',{srt:toSrt()});showSaved(result.saved);toast('✓ Downloaded SRT');});};
  bind('saveSrt',doSrt);bind('saveSrt2',doSrt);
  bind('translate',async()=>{if(!requireLines())return;await job('Translating into Khmer',async()=>{
    translateInProgress=true;const chunks=[],translated=[];for(let i=0;i<state.subtitles.length;i+=40)chunks.push(state.subtitles.slice(i,i+40));
    for(let c=0;c<chunks.length;c++){$('progressTitle').textContent='Translating subtitles';setProgress(Math.round(c/chunks.length*100),`AI បកប្រែភាគទី ${c+1} / ${chunks.length}`);
      const result=await native('translate',payload(chunks[c]));if(result.subtitles?.length!==chunks[c].length)throw Error('Translation line count mismatch');translated.push(...result.subtitles);}
    state.subtitles=translated;state.audioReady=false;setProgress(100,'Translation completed');renderSubs();toast(`✓ បកប្រែ ${translated.length} Subtitle រួចរាល់`);
  });});
  bind('recap',()=>{if(!requireLines())return;job('Generating film recap',async()=>{const subset=state.subtitles.slice(0,100);const result=await native('recap',payload(subset));$('recapArea').classList.remove('hidden');$('recapText').value=result.narration||'';toast('✓ បាន Generate Film Recap');});});
  bind('allFemale',()=>{state.subtitles.forEach(x=>x.voice='Female');state.audioReady=false;update();toast('♀ បានជ្រើស Female ទាំងអស់');});
  bind('allMale',()=>{state.subtitles.forEach(x=>x.voice='Male');state.audioReady=false;update();toast('♂ បានជ្រើស Male ទាំងអស់');});
  bind('preview',()=>{if(!requireLines())return;native('preview',{text:state.subtitles[0].text}).then(()=>toast('▷ Playing Android TTS preview')).catch(e=>toast(e.message));});
  bind('generateAudio',()=>{if(!requireLines())return;if(state.subtitles.length>100){toast('Server ទទួលយកច្រើនបំផុត 100 Subtitles ក្នុងមួយ Audio។ សូមបែងចែក Clip ជាមុន');return;}
    job('Generating Khmer voices',async()=>{const result=await native('audio',payload());state.audioReady=true;showSaved(result.saved);toast('✓ WAV Dubbing បានបង្កើតរួច');});});
  bind('exportVideo',()=>{if(!state.audioReady||!state.video){toast('សូម Generate Audio និង Import Video ជាមុន');return;}hideSaved();job('Exporting MP4',async()=>{const r=await native('export');showSaved(r.saved);toast('✓ Export MP4 រួចរាល់');});});
  bind('saveServer',()=>{job('Saving server settings',async()=>{const result=await native('setServer',{url:$('serverUrl').value});state.server=result.url;toast('✓ បានរក្សាទុក URL');});});
  bind('testServer',checkServer);
  native('getSettings').then(x=>{state.server=x.url||'';$('serverUrl').value=state.server;}).catch(()=>{});
  changeTab('import');
}
function changeTabAfterJob(name){setTimeout(()=>changeTab(name),0);/* busy state will be cleared by job finally */}
window.addEventListener('DOMContentLoaded',init);
