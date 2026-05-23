package com.tom.cpm.server;

/**
 * Inline HTML pages for the admin web dashboard.
 * These are served as fallback when the webapp/ resources are not on the classpath.
 * In production, these should be replaced with proper bundled SPA files.
 */
final class AdminPages {

    private AdminPages() {}

    static final String LOGIN = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>CPM Admin — Login</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui,-apple-system,sans-serif;background:#1a1a2e;color:#e0e0e0;
display:flex;justify-content:center;align-items:center;min-height:100vh}
.login-box{background:#16213e;padding:2.5rem;border-radius:12px;width:100%;max-width:400px;
box-shadow:0 8px 32px rgba(0,0,0,.3);border:1px solid #0f3460}
h1{text-align:center;margin-bottom:1.5rem;color:#e94560;font-size:1.5rem}
label{display:block;margin-bottom:.5rem;color:#a0a0b0;font-size:.9rem}
input{width:100%;padding:.75rem;margin-bottom:1.25rem;background:#0f3460;border:1px solid #1a1a4e;
color:#fff;border-radius:6px;font-size:1rem}
input:focus{outline:none;border-color:#e94560}
button{width:100%;padding:.75rem;background:#e94560;color:#fff;border:none;border-radius:6px;
font-size:1rem;cursor:pointer;transition:background .2s}
button:hover{background:#c23152}
.error{color:#e94560;text-align:center;margin-top:1rem;display:none;font-size:.9rem}
</style>
</head>
<body>
<div class="login-box">
<h1>CPM Admin Dashboard</h1>
<form id="loginForm">
<label for="username">Username</label>
<input type="text" id="username" name="username" required autocomplete="username">
<label for="password">Password</label>
<input type="password" id="password" name="password" required autocomplete="current-password">
<button type="submit">Sign In</button>
</form>
<div class="error" id="errorMsg"></div>
</div>
<script>
document.getElementById('loginForm').addEventListener('submit',async(e)=>{
e.preventDefault();
const u=document.getElementById('username').value;
const p=document.getElementById('password').value;
const err=document.getElementById('errorMsg');
try{
const r=await fetch('/api/admin/login',{
method:'POST',headers:{'Content-Type':'application/json'},
body:JSON.stringify({username:u,password:p})});
const d=await r.json();
if(r.ok){localStorage.setItem('cpm_token',d.token);
localStorage.setItem('cpm_user',d.username);
if(d.changePasswordRequired){localStorage.setItem('cpm_change_pwd','1')}else{localStorage.removeItem('cpm_change_pwd')}
window.location='/admin/index.html'}
else{err.textContent=d.error||'Login failed';err.style.display='block'}
}catch(e){err.textContent='Network error';err.style.display='block'}});
</script>
</body>
</html>""";

    static final String INDEX = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>CPM Admin — Models</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui,sans-serif;background:#1a1a2e;color:#e0e0e0;min-height:100vh}
nav{background:#16213e;padding:1rem 2rem;display:flex;justify-content:space-between;
align-items:center;border-bottom:1px solid #0f3460}
nav h1{color:#e94560;font-size:1.2rem}
nav a{color:#a0a0b0;text-decoration:none;margin-left:1.5rem;font-size:.9rem}
nav a:hover{color:#e94560}
.container{max-width:1200px;margin:2rem auto;padding:0 1rem}
.actions{display:flex;gap:1rem;margin-bottom:1.5rem;flex-wrap:wrap}
.actions input{padding:.5rem 1rem;background:#0f3460;border:1px solid #1a1a4e;
color:#fff;border-radius:6px;font-size:.9rem;min-width:200px}
.actions button{padding:.5rem 1.5rem;background:#e94560;color:#fff;border:none;
border-radius:6px;cursor:pointer;font-size:.9rem}
.actions button:hover{background:#c23152}
table{width:100%;border-collapse:collapse;background:#16213e;border-radius:8px;overflow:hidden}
th{background:#0f3460;padding:.75rem 1rem;text-align:left;font-size:.85rem;color:#a0a0b0}
td{padding:.75rem 1rem;border-bottom:1px solid #0f3460;font-size:.9rem}
tr:hover{background:#1a1a4e}
.badge{padding:.2rem .6rem;border-radius:4px;font-size:.75rem;font-weight:bold}
.badge-default{background:#0f3460;color:#4fc3f7}
.badge-forced{background:#4a1a2e;color:#e94560}
.delete-btn{background:transparent;border:1px solid #e94560;color:#e94560;padding:.25rem .75rem;
border-radius:4px;cursor:pointer;font-size:.8rem}
.delete-btn:hover{background:#e94560;color:#fff}
.logout{color:#e94560!important}
.overlay{position:fixed;inset:0;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;z-index:1000}
.overlay.hidden{display:none}
.modal{background:#16213e;border:1px solid #0f3460;border-radius:10px;padding:1.2rem;max-width:420px;width:92%}
.modal h2{font-size:1.05rem;color:#e94560;margin-bottom:.6rem}
.modal p{font-size:.88rem;color:#a0a0b0;margin-bottom:.8rem}
.modal input{width:100%;padding:.6rem;background:#0f3460;border:1px solid #1a1a4e;color:#fff;border-radius:6px;margin:.35rem 0}
.modal button{margin-top:.5rem;width:100%}
.modal .err{color:#e94560;font-size:.85rem;margin-top:.5rem;min-height:1.2rem}
</style>
</head>
<body>
<nav>
<h1>CPM Admin Dashboard</h1>
<div>
<a href="/admin/index.html">Models</a>
<a href="/admin/players.html">Players</a>
<a href="/admin/audit.html">Audit</a>
<a href="#" class="logout" onclick="logout()">Logout</a>
</div>
</nav>
<div class="container">
<div class="actions">
<input type="text" id="searchInput" placeholder="Search by player UUID or name...">
<button onclick="loadModels()">Refresh</button>
<button onclick="triggerBackup()">Backup DB</button>
</div>
<table>
<thead><tr>
<th>ID</th><th>Name</th><th>Owner</th><th>Size</th><th>Status</th><th>Created</th><th>Actions</th>
</tr></thead>
<tbody id="modelTable"><tr><td colspan="7" style="text-align:center">Loading...</td></tr></tbody>
</table>
</div>
<div id="pwdOverlay" class="overlay hidden">
<div class="modal">
<h2>Change Default Admin Password</h2>
<p>Your account is using the default password. You must change it before using the dashboard.</p>
<input type="password" id="curPwd" placeholder="Current password" autocomplete="current-password">
<input type="password" id="newPwd" placeholder="New password" autocomplete="new-password">
<button onclick="changePasswordRequired()">Change Password</button>
<div id="pwdErr" class="err"></div>
</div>
</div>
<script>
const token=localStorage.getItem('cpm_token');
if(!token)window.location='/admin/login.html';
let mustChange=localStorage.getItem('cpm_change_pwd')==='1';
async function api(url,opts={}){
if(mustChange && !url.startsWith('/api/admin/password')){
return {ok:false,status:428,json:async()=>({error:'Password change required',changePasswordRequired:true})};
}
const r=await fetch(url,{...opts,headers:{...opts.headers,'Authorization':'Bearer '+token}});
if(r.status===401){logout();return null}return r}
async function loadModels(){
if(mustChange){showPwdOverlay();return;}
const r=await api('/api/admin/models?size=100');
if(!r)return;
if(r.status===428){showPwdOverlay();return;}
const d=await r.json();
const tbody=document.getElementById('modelTable');
tbody.innerHTML=d.models.map(m=>`<tr>
<td>${m.id}</td><td>${esc(m.name)}</td><td>${esc(m.playerUuid).substring(0,8)}...</td>
<td>${(m.sizeBytes/1024).toFixed(1)} KB</td>
<td>${m.isDefault?'<span class="badge badge-default">DEFAULT</span> ':''}
${m.isForced?'<span class="badge badge-forced">FORCED</span>':''}</td>
<td>${m.createdAt?m.createdAt.substring(0,10):'-'}</td>
<td>
${m.isForced?'<button class="delete-btn" onclick="unforce('+m.id+')">Unforce</button>':
'<button class="delete-btn" onclick="forceModel('+m.id+')">Force</button>'}
<button class="delete-btn" onclick="deleteModel('+m.id+')">Delete</button>
</td></tr>`).join('')||'<tr><td colspan="7">No models found</td></tr>'}
async function deleteModel(id){
if(!confirm('Delete model #'+id+'?'))return;
await api('/api/admin/models/'+id,{method:'DELETE'});loadModels()}
async function forceModel(id){await api('/api/admin/models/'+id+'/force',{method:'PUT'});loadModels()}
async function unforce(id){await api('/api/admin/models/'+id+'/force',{method:'DELETE'});loadModels()}
async function triggerBackup(){await api('/api/admin/db/backup',{method:'POST'});alert('Backup triggered')}
function esc(s){return s?s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'):''}
function logout(){localStorage.clear();window.location='/admin/login.html'}
function showPwdOverlay(){document.getElementById('pwdOverlay').classList.remove('hidden')}
function hidePwdOverlay(){document.getElementById('pwdOverlay').classList.add('hidden')}
async function changePasswordRequired(){
const currentPassword=document.getElementById('curPwd').value;
const newPassword=document.getElementById('newPwd').value;
const err=document.getElementById('pwdErr');
err.textContent='';
if(!currentPassword||!newPassword){err.textContent='Current and new password are required';return;}
const r=await api('/api/admin/password',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({currentPassword,newPassword})});
if(!r){err.textContent='Request failed';return;}
const d=await r.json();
if(r.ok){
mustChange=false;
localStorage.removeItem('cpm_change_pwd');
hidePwdOverlay();
loadModels();
}else{
err.textContent=d.error||'Failed to change password';
}
}
document.getElementById('searchInput').addEventListener('input',function(){
const q=this.value.toLowerCase();
document.querySelectorAll('#modelTable tr').forEach(r=>{
r.style.display=r.textContent.toLowerCase().includes(q)?'':'none'})});
if(mustChange)showPwdOverlay();
loadModels();
</script>
</body>
</html>""";

    static final String MODEL_DETAIL = """
<!DOCTYPE html><html><head><meta charset="UTF-8"><title>Model Detail</title></head>
<body><h1>Model Detail</h1><p>Select a model from the list.</p>
<a href="/admin/index.html">Back to Models</a></body></html>""";

    static final String PLAYERS = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>CPM Admin — Players</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui,sans-serif;background:#1a1a2e;color:#e0e0e0;min-height:100vh}
nav{background:#16213e;padding:1rem 2rem;display:flex;justify-content:space-between;
align-items:center;border-bottom:1px solid #0f3460}
nav h1{color:#e94560;font-size:1.2rem}
nav a{color:#a0a0b0;text-decoration:none;margin-left:1.5rem;font-size:.9rem}
nav a:hover{color:#e94560}
.container{max-width:800px;margin:2rem auto;padding:0 1rem}
.card{background:#16213e;padding:1.5rem;border-radius:8px;margin-bottom:1rem;
border:1px solid #0f3460}
.card h3{margin-bottom:.5rem;color:#4fc3f7}
.card p{margin-bottom:.25rem;font-size:.9rem;color:#a0a0b0}
input{padding:.5rem 1rem;background:#0f3460;border:1px solid #1a1a4e;
color:#fff;border-radius:6px;font-size:.9rem;width:100%;margin-bottom:1rem}
button{padding:.5rem 1.5rem;background:#e94560;color:#fff;border:none;
border-radius:6px;cursor:pointer;font-size:.9rem}
.logout{color:#e94560!important}
</style>
</head>
<body>
<nav><h1>CPM Admin — Players</h1>
<div><a href="/admin/index.html">Models</a><a href="/admin/players.html">Players</a>
<a href="/admin/audit.html">Audit</a><a href="#" class="logout" onclick="logout()">Logout</a></div></nav>
<div class="container">
<input type="text" id="playerUuid" placeholder="Player UUID to manage...">
<button onclick="blockPlayer()">Block</button>
<button onclick="unblockPlayer()">Unblock</button>
<button onclick="playerInfo()">Info</button>
<div id="result" style="margin-top:1rem"></div>
</div>
<script>
const token=localStorage.getItem('cpm_token');
if(!token)window.location='/admin/login.html';
async function api(url,opts={}){
const r=await fetch(url,{...opts,headers:{...opts.headers,'Authorization':'Bearer '+token}});
if(r.status===401){logout();return null}return r}
async function blockPlayer(){
const u=document.getElementById('playerUuid').value;
const r=await api('/api/admin/players/'+u+'/block',{method:'PUT'});
document.getElementById('result').textContent=r?await r.text():'Error'}
async function unblockPlayer(){
const u=document.getElementById('playerUuid').value;
const r=await api('/api/admin/players/'+u+'/block',{method:'DELETE'});
document.getElementById('result').textContent=r?await r.text():'Error'}
async function playerInfo(){
const u=document.getElementById('playerUuid').value;document.getElementById('result').textContent='Player: '+u}
function logout(){localStorage.clear();window.location='/admin/login.html'}
</script>
</body>
</html>""";

    static final String AUDIT = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>CPM Admin — Audit Log</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui,sans-serif;background:#1a1a2e;color:#e0e0e0;min-height:100vh}
nav{background:#16213e;padding:1rem 2rem;display:flex;justify-content:space-between;
align-items:center;border-bottom:1px solid #0f3460}
nav h1{color:#e94560;font-size:1.2rem}
nav a{color:#a0a0b0;text-decoration:none;margin-left:1.5rem;font-size:.9rem}
nav a:hover{color:#e94560}
.container{max-width:1000px;margin:2rem auto;padding:0 1rem}
.entry{background:#16213e;padding:1rem;border-radius:6px;margin-bottom:.5rem;
border:1px solid #0f3460;font-size:.85rem;font-family:monospace}
.entry .actor{color:#4fc3f7}.entry .action{color:#e94560}.entry .time{color:#a0a0b0}
button{padding:.5rem 1.5rem;background:#e94560;color:#fff;border:none;
border-radius:6px;cursor:pointer;font-size:.9rem;margin-right:.5rem}
.logout{color:#e94560!important}
</style>
</head>
<body>
<nav><h1>CPM Admin — Audit Log</h1>
<div><a href="/admin/index.html">Models</a><a href="/admin/players.html">Players</a>
<a href="/admin/audit.html">Audit</a><a href="#" class="logout" onclick="logout()">Logout</a></div></nav>
<div class="container">
<div style="margin-bottom:1rem">
<button onclick="loadAudit(0)">Refresh</button>
<button onclick="loadAudit(page-1)" id="prevBtn" disabled>Previous</button>
<button onclick="loadAudit(page+1)" id="nextBtn">Next</button>
<span id="pageInfo" style="margin-left:1rem;color:#a0a0b0"></span>
</div>
<div id="entries">Loading...</div>
</div>
<script>
const token=localStorage.getItem('cpm_token');let page=0;
if(!token)window.location='/admin/login.html';
async function api(url,opts={}){
const r=await fetch(url,{...opts,headers:{...opts.headers,'Authorization':'Bearer '+token}});
if(r.status===401){logout();return null}return r}
async function loadAudit(p){
page=Math.max(0,p);
const r=await api('/api/admin/audit?page='+page+'&size=50');
if(!r)return;
const d=await r.json();
document.getElementById('entries').innerHTML=d.entries.map(e=>
'<div class="entry">'+esc(e)+'</div>').join('')||'No entries';
document.getElementById('pageInfo').textContent='Page '+page;
document.getElementById('prevBtn').disabled=page===0}
function esc(s){return s?s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'):''}
function logout(){localStorage.clear();window.location='/admin/login.html'}
loadAudit(0);
</script>
</body>
</html>""";
}
