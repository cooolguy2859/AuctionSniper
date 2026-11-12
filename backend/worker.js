const H={"content-type":"application/json; charset=utf-8","cache-control":"no-store"};const R=(x,s=200)=>new Response(JSON.stringify(x),{status:s,headers:H});const T=(x,n=96)=>typeof x==="string"&&x.length>0&&x.length<=n&&!/[\u0000-\u001f]/.test(x);const N=x=>Number.isSafeInteger(x)&&x>=0&&x<=9000000000000000;const Q=(d,s,...a)=>d.prepare(s).bind(...a);const B=async r=>{try{return await r.json()}catch{return null}};export default{async fetch(r,e){const u=new URL(r.url),p=u.pathname,m=r.method;if(m==="GET"&&p==="/health")return R({ok:true,service:"auction-sniper-api",version:2,features:["market","movers","networth","orders","teams","balances","events","ender-share"]});if(!e.API_KEY||(r.headers.get("authorization")!==`Bearer ${e.API_KEY}`&&r.headers.get("x-market-key")!==e.API_KEY))return R({error:"Unauthorized"},401);if(!e.DB)return R({error:"DB missing"},503);const s=u.searchParams.get("serverId"),now=Date.now();if(m==="POST"&&p==="/v1/observations"){const b=await B(r);if(!b||!T(b.serverId,80)||!T(b.itemId,128)||!T(b.itemName)||!N(b.price))return R({error:"Invalid fields"},400);await Q(e.DB,"INSERT INTO observations(server_id,item_id,item_name,price,player,observed_at) VALUES(?,?,?,?,?,?)",b.serverId,b.itemId,b.itemName,b.price,b.player||null,now).run();return R({ok:true})}if(m==="GET"&&p==="/v1/prices"){if(!T(s,80))return R({error:"serverId required"},400);const x=await Q(e.DB,"SELECT item_id AS itemId,MAX(item_name) AS itemName,ROUND(AVG(price)) AS average,MIN(price) AS low,MAX(price) AS high,COUNT(*) AS samples,MAX(observed_at) AS lastSeen FROM observations WHERE server_id=? GROUP BY item_id ORDER BY lastSeen DESC LIMIT 250",s).all();return R({serverId:s,prices:x.results||[]})}if(m==="GET"&&p==="/v1/history"){const id=u.searchParams.get("itemId");if(!T(s,80)||!T(id,128))return R({error:"serverId/itemId required"},400);const x=await Q(e.DB,"SELECT price,player,observed_at AS observedAt FROM observations WHERE server_id=? AND item_id=? ORDER BY observed_at DESC LIMIT 100",s,id).all();return R({history:x.results||[]})}if(m==="GET"&&p==="/v1/movers"){if(!T(s,80))return R({error:"serverId required"},400);const x=await Q(e.DB,"SELECT item_id AS ticker,ROUND(AVG(price)) AS price,COUNT(*) AS vol,MAX(observed_at) AS lastSeen FROM observations WHERE server_id=? AND observed_at>? GROUP BY item_id ORDER BY vol DESC LIMIT 100",s,now-86400000).all();const a=(x.results||[]).map(v=>({...v,chg:0}));return R({gainers:[],losers:[],traded:a})}if(m==="POST"&&p==="/v1/networth/report"){const b=await B(r);if(!b||!T(b.serverId,80)||!T(b.player,32)||!N(b.worth))return R({error:"Invalid report"},400);await Q(e.DB,"INSERT INTO networth_reports(player,server_id,worth,is_private,updated_at) VALUES(?,?,?,?,?)",b.player,b.serverId,b.worth,b.isPrivate?1:0,now).run();return R({ok:true})}if(m==="GET"&&p==="/v1/networth/top"){if(!T(s,80))return R({error:"serverId required"},400);const x=await Q(e.DB,"SELECT player AS name,MAX(worth) AS worth FROM networth_reports WHERE server_id=? AND is_private=0 GROUP BY player ORDER BY worth DESC LIMIT 100",s).all();return R({players:x.results||[]})}if(m==="GET"&&p==="/v1/networth/series"){const player=u.searchParams.get("player");if(!T(s,80)||!T(player,32))return R({error:"Missing player"},400);const x=await Q(e.DB,"SELECT updated_at AS time,worth FROM networth_reports WHERE server_id=? AND player=? ORDER BY updated_at DESC LIMIT 30",s,player).all();return R({points:(x.results||[]).reverse().map(v=>[v.time,v.worth])})}if(m==="POST"&&p==="/v1/orders"){const b=await B(r);if(!b||!T(b.serverId,80)||!Array.isArray(b.orders)||b.orders.length>500)return R({error:"Invalid orders"},400);const a=b.orders.filter(x=>T(x.ticker,96)&&N(x.bid)&&N(x.demand)&&N(x.orders)&&N(x.listed)).map(x=>Q(e.DB,"INSERT INTO order_book(server_id,ticker,bid,demand,orders,listed,material,depth_json,updated_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(server_id,ticker) DO UPDATE SET bid=excluded.bid,demand=excluded.demand,orders=excluded.orders,listed=excluded.listed,material=excluded.material,depth_json=excluded.depth_json,updated_at=excluded.updated_at",b.serverId,x.ticker,x.bid,x.demand,x.orders,x.listed,x.material||null,JSON.stringify(x.depth||[]),now));if(a.length)await e.DB.batch(a);if(a.length){const history=b.orders.filter(x=>T(x.ticker,96)&&N(x.bid)&&N(x.demand)&&N(x.orders)).map(x=>Q(e.DB,"INSERT INTO order_book_history(server_id,ticker,bid,demand,orders,observed_at) VALUES(?,?,?,?,?,?)",b.serverId,x.ticker,x.bid,x.demand,x.orders,now));if(history.length)await e.DB.batch(history)}return R({ok:true,saved:a.length})}if(m==="GET"&&p==="/v1/orders"){if(!T(s,80))return R({error:"serverId required"},400);const x=await Q(e.DB,"SELECT ticker,bid,demand,orders,listed,material,depth_json AS depthJson FROM order_book WHERE server_id=? ORDER BY bid DESC LIMIT 500",s).all();return R({orders:(x.results||[]).map(v=>({...v,depth:JSON.parse(v.depthJson||"[]")}))})}if(m==="POST"&&p==="/v1/team/report"){const b=await B(r);if(!b||!T(b.serverId,80)||!T(b.team,64)||!T(b.leader,32)||!N(b.worth)||!N(b.spawners)||!N(b.tracked)||!N(b.size))return R({error:"Invalid team"},400);await Q(e.DB,"INSERT INTO team_reports(server_id,team_name,leader,worth,spawners,tracked,total_members,verified,updated_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(server_id,team_name) DO UPDATE SET leader=excluded.leader,worth=excluded.worth,spawners=excluded.spawners,tracked=excluded.tracked,total_members=excluded.total_members,verified=excluded.verified,updated_at=excluded.updated_at",b.serverId,b.team,b.leader,b.worth,b.spawners,b.tracked,b.size,b.verified?1:0,now).run();return R({ok:true})}if(m==="GET"&&p==="/v1/team/top"){if(!T(s,80))return R({error:"serverId required"},400);const x=await Q(e.DB,"SELECT team_name AS team,leader,worth AS value,spawners AS spawnerValue,tracked,total_members AS size,verified FROM team_reports WHERE server_id=? ORDER BY worth DESC LIMIT 100",s).all();return R({teams:(x.results||[]).map((v,i)=>({...v,rank:i+1}))})}if(m==="GET"&&p==="/v1/team"){const name=u.searchParams.get("name");if(!T(s,80)||!T(name,64))return R({error:"Missing name"},400);const x=await Q(e.DB,"SELECT team_name AS team,leader,worth AS value,spawners AS spawnerValue,tracked,total_members AS size,verified FROM team_reports WHERE server_id=? AND lower(team_name)=lower(?)",s,name).first();return R(x?{found:true,...x,members:[]}:{found:false,members:[]})}if(m==="POST"&&p==="/v1/balances"){const b=await B(r);if(!b||!T(b.serverId,80)||!T(b.player,32)||!N(b.balance))return R({error:"Invalid balance"},400);await Q(e.DB,"INSERT INTO player_balances(server_id,player,balance,updated_at) VALUES(?,?,?,?) ON CONFLICT(server_id,player) DO UPDATE SET balance=excluded.balance,updated_at=excluded.updated_at",b.serverId,b.player,b.balance,now).run();return R({ok:true})}if(m==="GET"&&p==="/v1/balances/top"){if(!T(s,80))return R({error:"serverId required"},400);const x=await Q(e.DB,"SELECT player AS name,balance FROM player_balances WHERE server_id=? ORDER BY balance DESC LIMIT 100",s).all();return R({players:x.results||[]})}if(m==="POST"&&p==="/v1/events"){const b=await B(r);if(!b||!T(b.serverId,80)||!T(b.player,32)||!T(b.type,24)||!N(b.price)||!N(b.quantity))return R({error:"Invalid event"},400);await Q(e.DB,"INSERT INTO player_events(server_id,player,event_type,item_name,price,quantity,occurred_at) VALUES(?,?,?,?,?,?,?)",b.serverId,b.player,b.type,b.item||null,b.price,b.quantity,now).run();return R({ok:true})}if(m==="GET"&&p==="/v1/events"){if(!T(s,80))return R({error:"serverId required"},400);const player=u.searchParams.get("player"),x=player?await Q(e.DB,"SELECT player,event_type AS type,item_name AS item,price,quantity,occurred_at AS time FROM player_events WHERE server_id=? AND player=? ORDER BY occurred_at DESC LIMIT 100",s,player).all():await Q(e.DB,"SELECT player,event_type AS type,item_name AS item,price,quantity,occurred_at AS time FROM player_events WHERE server_id=? ORDER BY occurred_at DESC LIMIT 100",s).all();return R({events:x.results||[]})}if(m==="POST"&&p==="/v1/ender-share"){const b=await B(r);if(!b||!T(b.serverId,80)||!T(b.player,32)||typeof b.shared!=="boolean"||!Array.isArray(b.items)||b.items.length>54||!N(b.value))return R({error:"Invalid share"},400);await Q(e.DB,"INSERT INTO ender_shares(server_id,player,shared,value,items_json,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(server_id,player) DO UPDATE SET shared=excluded.shared,value=excluded.value,items_json=excluded.items_json,updated_at=excluded.updated_at",b.serverId,b.player,b.shared?1:0,b.value,JSON.stringify(b.items),now).run();return R({ok:true})}if(m==="GET"&&p==="/v1/ender-share"){const player=u.searchParams.get("player");if(!T(s,80)||!T(player,32))return R({error:"Missing player"},400);const x=await Q(e.DB,"SELECT player,shared,value,items_json,updated_at FROM ender_shares WHERE server_id=? AND lower(player)=lower(?)",s,player).first();return R(x?{shared:!!x.shared,player:x.player,value:x.value,updated:x.updated_at,items:JSON.parse(x.items_json||"[]")}:{shared:false,player,updated:0,value:0,items:[]})}
const server=u.searchParams.get("serverId")||r.headers.get("x-server-id")||"lifesteal.net";
const who=r.headers.get("x-user")||u.searchParams.get("user")||"";
const plain=(x,status=200)=>new Response(x,{status,headers:{"content-type":"text/plain; charset=utf-8","cache-control":"no-store"}});
const tokenFor=async player=>{
 if(!T(player,32))return "";
 const old=await Q(e.DB,"SELECT token FROM player_accounts WHERE server_id=? AND lower(player)=lower(?)",server,player).first();
 if(old&&old.token)return old.token;
 const token=crypto.randomUUID()+crypto.randomUUID().replace(/-/g,"");
 await Q(e.DB,"INSERT INTO player_accounts(server_id,player,token,created_at,last_seen) VALUES(?,?,?,?,?) ON CONFLICT(server_id,player) DO UPDATE SET token=excluded.token,last_seen=excluded.last_seen",server,player,token,now,now).run();
 return token;
};
const validUser=async player=>{if(!T(player,32))return false;const sent=r.headers.get("x-token")||"";if(!sent)return false;const row=await Q(e.DB,"SELECT token FROM player_accounts WHERE server_id=? AND lower(player)=lower(?)",server,player).first();return !!row&&row.token===sent;};
const tickers=async()=>{
 const z=await Q(e.DB,"SELECT item_id AS ticker,MAX(item_name) AS name,ROUND(AVG(price)) AS last,MIN(price) AS floor,COUNT(*) AS sales,MAX(observed_at) AS seen FROM observations WHERE server_id=? AND observed_at>? GROUP BY item_id ORDER BY seen DESC LIMIT 300",server,now-604800000).all();
 const out=[];
 for(const v of z.results||[]){
  const o=await Q(e.DB,"SELECT COALESCE(SUM(orders),0) AS supply,COALESCE(MAX(bid),0) AS bid FROM order_book WHERE server_id=? AND lower(ticker)=lower(?)",server,v.ticker).first();
  out.push({ticker:v.ticker,last:Number(v.last||0),floor:Number(v.floor||v.last||0),firm:Number(v.sales||0)>=3,supply:Number(o&&o.supply||0),sales:Number(v.sales||0)});
 }
 return out;
};
if(m==="GET"&&p==="/api/version")return R({version:"0.1.0",announce:false,beta:false,request_url:"https://discord.gg/E6aS3vuAd"});
if(m==="GET"&&p==="/api/provision")return R({error:"Mojang verification is required before provisioning"},403);
if(m==="GET"&&p==="/api/auth/challenge"){
 const player=u.searchParams.get("user")||who;if(!T(player,32))return R({error:"Invalid player"},400);
 const nonce=crypto.randomUUID()+crypto.randomUUID();
 await Q(e.DB,"INSERT INTO auth_challenges(server_id,player,nonce,expires_at) VALUES(?,?,?,?) ON CONFLICT(server_id,player) DO UPDATE SET nonce=excluded.nonce,expires_at=excluded.expires_at",server,player,nonce,now+120000).run();return R({nonce});
}
if(m==="POST"&&p==="/api/auth/verify"){
 const nonce=(await r.text()).trim();if(!T(nonce,100))return R({error:"Invalid challenge"},400);
 const ch=await Q(e.DB,"SELECT player,expires_at FROM auth_challenges WHERE server_id=? AND nonce=?",server,nonce).first();
 if(!ch||Number(ch.expires_at)<now)return R({error:"Challenge expired"},403);
 const mojang=await fetch("https://sessionserver.mojang.com/session/minecraft/hasJoined?username="+encodeURIComponent(ch.player)+"&serverId="+encodeURIComponent(nonce));if(!mojang.ok)return R({error:"Mojang did not confirm this session"},403);const profile=await B(mojang);if(!profile||typeof profile.name!=="string"||profile.name.toLowerCase()!==ch.player.toLowerCase())return R({error:"Mojang account mismatch"},403);const token=await tokenFor(ch.player);await Q(e.DB,"DELETE FROM auth_challenges WHERE server_id=? AND player=?",server,ch.player).run();return R({token,uuid:profile.id||null});
}
if(m==="GET"&&p==="/api/access"){
 const player=u.searchParams.get("user")||who;if(!await validUser(player))return R({error:"Account verification required"},403);const row=await Q(e.DB,"SELECT token FROM player_accounts WHERE server_id=? AND lower(player)=lower(?)",server,player).first();const sent=r.headers.get("x-token")||"";
 return R({tokenOwner:row&&row.token===sent?player:null,welcome:false,onboardAlways:false,refNext:0,refNextTag:null,referrals:0});
}
if(m==="GET"&&p==="/api/cosmetics")return R({themes:[],items:[],loyalty:{days:0,granted:false}});
if(m==="POST"&&p==="/api/alive"){
 const player=(await r.text()).trim()||who;if(!T(player,32)||!await validUser(player))return R({error:"Verified account required"},403);
 await Q(e.DB,"INSERT INTO player_presence(server_id,player,seen_at) VALUES(?,?,?) ON CONFLICT(server_id,player) DO UPDATE SET seen_at=excluded.seen_at",server,player,now).run();
 await Q(e.DB,"UPDATE player_accounts SET last_seen=? WHERE server_id=? AND lower(player)=lower(?)",now,server,player).run();return R({ok:true});
}
if(m==="POST"&&p==="/api/networth/report"){
 const parts=(await r.text()).trim().split(/\t/);const player=parts[0]||who;const worth=Number(parts[1]);const priv=parts[2]==="1";
 if(!T(player,32)||!await validUser(player))return R({error:"Verified account required"},403);if(!N(worth))return R({error:"Invalid net worth"},400);
 await Q(e.DB,"INSERT INTO networth_snapshots(server_id,player,worth,is_private,updated_at) VALUES(?,?,?,?,?)",server,player,worth,priv?1:0,now).run();
 await Q(e.DB,"INSERT INTO networth_reports(player,server_id,worth,is_private,updated_at) VALUES(?,?,?,?,?)",player,server,worth,priv?1:0,now).run();return R({ok:true});
}
if(m==="GET"&&p==="/api/networth/top"){
 const z=await Q(e.DB,"SELECT n.player AS name,n.worth AS networth FROM networth_snapshots n JOIN (SELECT player,MAX(updated_at) AS t FROM networth_snapshots WHERE server_id=? GROUP BY player) q ON q.player=n.player AND q.t=n.updated_at WHERE n.server_id=? AND n.is_private=0 ORDER BY n.worth DESC LIMIT 100",server,server).all();
 return R((z.results||[]).map((v,i)=>({rank:i+1,name:v.name,networth:Number(v.networth)})));
}
if(m==="GET"&&p==="/api/networth"){
 const player=u.searchParams.get("player")||"";const x=await Q(e.DB,"SELECT worth,is_private FROM networth_snapshots WHERE server_id=? AND lower(player)=lower(?) ORDER BY updated_at DESC LIMIT 1",server,player).first();
 if(!x)return R({found:false});if(x.is_private)return R({found:true,private:true,name:player});
 const z=await Q(e.DB,"SELECT player,worth FROM networth_snapshots WHERE server_id=? AND updated_at=(SELECT MAX(updated_at) FROM networth_snapshots n2 WHERE n2.server_id=networth_snapshots.server_id AND lower(n2.player)=lower(networth_snapshots.player)) AND is_private=0 ORDER BY worth DESC",server).all();
 const rank=(z.results||[]).findIndex(v=>v.player.toLowerCase()===player.toLowerCase())+1;return R({found:true,private:false,name:player,networth:Number(x.worth),rank:rank||0});
}
if(m==="GET"&&p==="/api/networth/series"){
 const player=u.searchParams.get("user")||u.searchParams.get("player")||"";
 const z=await Q(e.DB,"SELECT updated_at AS t,worth FROM networth_snapshots WHERE server_id=? AND lower(player)=lower(?) ORDER BY updated_at DESC LIMIT 100",server,player).all();
 return R({points:(z.results||[]).reverse().map(v=>[Number(v.t),Number(v.worth)])});
}
if(m==="GET"&&p==="/api/referral/tags"){
 const z=await Q(e.DB,"SELECT player,tag FROM referral_tags WHERE server_id=?",server).all();const out={};for(const v of z.results||[])out[v.player]=v.tag;return R(out);
}
if(m==="POST"&&p==="/api/referral"){
 const ref=(await r.text()).trim();const player=who;if(!T(player,32))return plain("badname",400);if(!await validUser(player))return plain("unverified",403);
 if(!T(ref,32)||!/^[A-Za-z0-9_]{1,16}$/.test(ref))return plain("badname",400);
 if(ref.toLowerCase()===player.toLowerCase())return plain("self");
 const old=await Q(e.DB,"SELECT referrer FROM player_referrals WHERE server_id=? AND lower(player)=lower(?)",server,player).first();if(old)return plain("already");
 await Q(e.DB,"INSERT INTO player_referrals(server_id,player,referrer,created_at) VALUES(?,?,?,?)",server,player,ref,now).run();
 await Q(e.DB,"INSERT OR IGNORE INTO referral_tags(server_id,player,tag) VALUES(?,?,?)",server,player,"§b✦ "+player);return plain("oklink");
}
if(m==="POST"&&p==="/api/link")return plain("Discord linking is not configured on this Worker.",503);
if(m==="POST"&&p==="/api/alerts"){
 const lines=(await r.text()).split(/\r?\n/);const head=(lines.shift()||"").split(/\t/);const player=head[0]||who;if(!T(player,32)||!await validUser(player))return R({error:"Verified account required"},403);
 const stmts=[];for(const line of lines){const a=line.split(/\t/);if(!T(a[0],96))continue;const target=Number(a[1]);if(!N(target))continue;
 stmts.push(Q(e.DB,"INSERT INTO player_alerts(server_id,player,ticker,target,below,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(server_id,player,ticker) DO UPDATE SET target=excluded.target,below=excluded.below,updated_at=excluded.updated_at",server,player,a[0],target,a[2]==="1"?1:0,now));}
 if(stmts.length)await e.DB.batch(stmts);return R({ok:true,saved:stmts.length});
}
if(m==="GET"&&p==="/api/tickers")return R(await tickers());
if(m==="GET"&&p==="/api/stream"){const data=await tickers();return new Response("data:"+JSON.stringify(data)+"\n\n",{headers:{"content-type":"text/event-stream; charset=utf-8","cache-control":"no-cache, no-transform","connection":"keep-alive"}});}
if(m==="GET"&&p==="/api/orders"){
 const z=await Q(e.DB,"SELECT ticker,bid,demand,orders,listed,material,depth_json FROM order_book WHERE server_id=? ORDER BY bid DESC LIMIT 500",server).all();
 return R((z.results||[]).map(v=>({ticker:v.ticker,bid:Number(v.bid||0),demand:Number(v.demand||0),orders:Number(v.orders||0),listed:Number(v.listed||0),material:v.material,depth:JSON.parse(v.depth_json||"[]")})));
}
if(m==="GET"&&p==="/api/movers"){
 const z=await Q(e.DB,"SELECT item_id AS ticker,price,observed_at AS t FROM observations WHERE server_id=? AND observed_at>? ORDER BY observed_at ASC LIMIT 10000",server,now-86400000).all();
 const map=new Map();for(const v of z.results||[]){let a=map.get(v.ticker);if(!a){a={ticker:v.ticker,first:Number(v.price),last:Number(v.price),vol:0};map.set(v.ticker,a);}a.last=Number(v.price);a.vol++;}
 const all=[...map.values()].filter(v=>v.first>0&&v.last>0).map(v=>({...v,price:v.last,chg:(v.last-v.first)*100/v.first}));
 const gainers=all.filter(v=>v.chg>0).sort((a,b)=>b.chg-a.chg).slice(0,20);const losers=all.filter(v=>v.chg<0).sort((a,b)=>a.chg-b.chg).slice(0,20);const traded=all.sort((a,b)=>b.vol-a.vol).slice(0,20);
 const fmt=a=>a.map(v=>({ticker:v.ticker,chg:v.chg,price:v.price,vol:v.vol}));return R({gainers:fmt(gainers),losers:fmt(losers),traded:fmt(traded)});
}
if(m==="POST"&&p==="/api/team/report"){
 if(!T(who,32)||!await validUser(who))return R({error:"Verified account required"},403);
 const lines=(await r.text()).split(/\r?\n/);const first=(lines.shift()||"").split(/\t/);if(first[0]!=="TEAM"||!T(first[1],64))return R({error:"Invalid team report"},400);
 const team=first[1],members=[],spawners=[];let leader=who;
 for(const line of lines){const a=line.split(/\t/);if(a[0]==="R"&&T(a[1],32)){for(const name of (a[2]||"").split(",")){const n=name.trim();if(/^[A-Za-z0-9_]{1,16}$/.test(n)){members.push({role:a[1],name:n});if(a[1].toLowerCase().includes("leader"))leader=n;}}}else if(a[0]==="S"&&T(a[1],64)){const count=Number(a[2]);if(N(count))spawners.push({kind:a[1],count});}}
 const worths=[];for(const mm of members){const q=await Q(e.DB,"SELECT worth FROM networth_snapshots WHERE server_id=? AND lower(player)=lower(?) ORDER BY updated_at DESC LIMIT 1",server,mm.name).first();mm.worth=Number(q&&q.worth||0);mm.tracked=!!q;if(mm.worth)worths.push(mm.worth);}
 const totalWorth=worths.reduce((a,b)=>a+b,0),spawnerTotal=spawners.reduce((a,b)=>a+b.count,0),tracked=members.filter(x=>x.tracked).length;
 await Q(e.DB,"INSERT INTO team_reports(server_id,team_name,leader,worth,spawners,tracked,total_members,verified,updated_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(server_id,team_name) DO UPDATE SET leader=excluded.leader,worth=excluded.worth,spawners=excluded.spawners,tracked=excluded.tracked,total_members=excluded.total_members,updated_at=excluded.updated_at",server,team,leader,totalWorth,spawnerTotal,tracked,members.length,0,now).run();
 await Q(e.DB,"DELETE FROM team_members WHERE server_id=? AND team_name=?",server,team).run();await Q(e.DB,"DELETE FROM team_spawners WHERE server_id=? AND team_name=?",server,team).run();
 const batch=[];for(const mm of members)batch.push(Q(e.DB,"INSERT INTO team_members(server_id,team_name,role,player,updated_at) VALUES(?,?,?,?,?)",server,team,mm.role,mm.name,now));for(const sp of spawners)batch.push(Q(e.DB,"INSERT INTO team_spawners(server_id,team_name,kind,count,updated_at) VALUES(?,?,?,?,?)",server,team,sp.kind,sp.count,now));
 if(batch.length)await e.DB.batch(batch);return R({ok:true,members:members.length});
}
if(m==="GET"&&p==="/api/team/top"){
 const z=await Q(e.DB,"SELECT team_name AS team,leader,worth AS value,spawners AS spawnerValue,tracked,total_members AS size,verified FROM team_reports WHERE server_id=? ORDER BY worth DESC LIMIT 100",server).all();return R((z.results||[]).map((v,i)=>({...v,rank:i+1})));
}
if(m==="GET"&&p==="/api/team"){
 const name=u.searchParams.get("name")||"";const v=await Q(e.DB,"SELECT team_name AS team,leader,worth AS value,spawners AS spawnerValue,tracked,total_members AS size,verified FROM team_reports WHERE server_id=? AND lower(team_name)=lower(?)",server,name).first();if(!v)return R({found:false,members:[]});
 const z=await Q(e.DB,"SELECT role,player AS name FROM team_members WHERE server_id=? AND team_name=? ORDER BY CASE WHEN lower(role) LIKE '%leader%' THEN 0 ELSE 1 END,player",server,v.team).all();const members=[];
 for(const mm of z.results||[]){const w=await Q(e.DB,"SELECT worth FROM networth_snapshots WHERE server_id=? AND lower(player)=lower(?) ORDER BY updated_at DESC LIMIT 1",server,mm.name).first();members.push({role:mm.role,name:mm.name,worth:Number(w&&w.worth||0),tracked:!!w});}return R({...v,found:true,members});
}
if(m==="POST"&&p==="/api/ec/report"){
 const lines=(await r.text()).split(/\r?\n/);const h=(lines.shift()||"").split(/\t/);const shared=h[1]==="1";const value=Number(h[2]||0);if(!T(who,32)||!await validUser(who))return R({error:"Verified account required"},403);
 const items=[];for(const line of lines){const a=line.split(/\t/);if(a.length<3)continue;const slot=Number(a[0]),count=Number(a[2]);if(!Number.isInteger(slot)||slot<0||slot>53||!T(a[1],128)||!Number.isInteger(count)||count<1)continue;items.push({slot,id:a[1],count,ticker:a[3]||"",name:a[4]||"",nbt:a[5]||""});}
 const json=JSON.stringify(items);await Q(e.DB,"INSERT INTO player_ender_share(server_id,player,shared,value,items_json,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(server_id,player) DO UPDATE SET shared=excluded.shared,value=excluded.value,items_json=excluded.items_json,updated_at=excluded.updated_at",server,who,shared?1:0,N(value)?value:0,json,now).run();
 await Q(e.DB,"INSERT INTO ender_shares(server_id,player,shared,value,items_json,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(server_id,player) DO UPDATE SET shared=excluded.shared,value=excluded.value,items_json=excluded.items_json,updated_at=excluded.updated_at",server,who,shared?1:0,N(value)?value:0,json,now).run();return R({ok:true});
}
if(m==="GET"&&p==="/api/ec"){
 const player=u.searchParams.get("player")||"";const v=await Q(e.DB,"SELECT shared,value,items_json,updated_at FROM player_ender_share WHERE server_id=? AND lower(player)=lower(?)",server,player).first();
 return R(v?{shared:!!v.shared,player,updated:Number(v.updated_at),value:Number(v.value),items:JSON.parse(v.items_json||"[]")}:{shared:false,player,updated:0,value:0,items:[]});
}

if(m==="GET"&&p==="/api/series"){
 const ticker=u.searchParams.get("ticker")||"";if(!T(ticker,128))return R({error:"ticker required"},400);
 const raw=u.searchParams.get("range")||"30d";const days=Math.min(365,Math.max(1,Number.parseInt(raw,10)||30));
 const z=await Q(e.DB,"SELECT price,observed_at AS t FROM observations WHERE server_id=? AND lower(item_id)=lower(?) AND observed_at>? ORDER BY observed_at ASC LIMIT 1500",server,ticker,now-days*86400000).all();
 const rows=z.results||[];const pts=rows.map(v=>[Number(v.t),Number(v.price)]);const vals=rows.map(v=>Number(v.price)).filter(v=>v>0);
 const current=await Q(e.DB,"SELECT listed,orders FROM order_book WHERE server_id=? AND lower(ticker)=lower(?)",server,ticker).first();
 const last=vals.length?vals[vals.length-1]:0;const floor=vals.length?Math.min(...vals):null;const vwap=vals.length?Math.round(vals.reduce((a,b)=>a+b,0)/vals.length):0;
 return R({price:last,floor,supply:current?Number(current.listed||0):null,vwap,vol:rows.length,sales:rows.length,firm:rows.length>=3,points:pts,high:vals.length?Math.max(...vals):0,low:floor??0,chg:vals.length>1&&vals[0]>0?(last-vals[0])*100/vals[0]:0});
}
if(m==="GET"&&p==="/api/orders/series"){
 const ticker=u.searchParams.get("ticker")||"";if(!T(ticker,128))return R({error:"ticker required"},400);
 const raw=u.searchParams.get("range")||"30d";const days=Math.min(365,Math.max(1,Number.parseInt(raw,10)||30));
 const z=await Q(e.DB,"SELECT bid,demand,orders,observed_at AS t FROM order_book_history WHERE server_id=? AND lower(ticker)=lower(?) AND observed_at>? ORDER BY observed_at ASC LIMIT 1500",server,ticker,now-days*86400000).all();
 let rows=z.results||[];if(!rows.length){const cur=await Q(e.DB,"SELECT bid,demand,orders,updated_at AS t FROM order_book WHERE server_id=? AND lower(ticker)=lower(?)",server,ticker).first();if(cur&&Number(cur.bid)>0)rows=[cur];}if(!rows.length){const obs=await Q(e.DB,"SELECT price AS bid,0 AS demand,1 AS orders,observed_at AS t FROM observations WHERE server_id=? AND lower(item_id)=lower(?) AND observed_at>? ORDER BY observed_at ASC LIMIT 1500",server,ticker,now-days*86400000).all();rows=obs.results||[];}
 const bids=rows.map(v=>Number(v.bid||0)).filter(v=>v>0);const pts=rows.map(v=>[Number(v.t),Number(v.bid||0)]);
 const cur=rows.length?rows[rows.length-1]:{bid:0,demand:0,orders:0};const first=bids.length?bids[0]:0,last=bids.length?bids[bids.length-1]:0;
 return R({bid:last,demand:Number(cur.demand||0),orders:Number(cur.orders||0),high:bids.length?Math.max(...bids):0,low:bids.length?Math.min(...bids):0,chg:first>0?(last-first)*100/first:0,avg:bids.length?Math.round(bids.reduce((a,b)=>a+b,0)/bids.length):0,points:pts});
}
if(m==="GET"&&p==="/api/book"){
 const ticker=u.searchParams.get("ticker")||"";if(!T(ticker,128))return R([]);
 const cur=await Q(e.DB,"SELECT bid,depth_json FROM order_book WHERE server_id=? AND lower(ticker)=lower(?)",server,ticker).first();
 if(!cur){const observed=await Q(e.DB,"SELECT price,player AS seller FROM observations WHERE server_id=? AND lower(item_id)=lower(?) ORDER BY observed_at DESC LIMIT 1",server,ticker).first();return R(observed?[{price:Number(observed.price),seller:observed.seller||"",qty:0}]:[]);}
 let depth=[];try{depth=JSON.parse(cur.depth_json||"[]")}catch{}
 if(!Array.isArray(depth))depth=[];
 const out=depth.map(v=>({price:Number(v.price||0),seller:v.seller||"",qty:Number(v.qty||0)})).filter(v=>v.price>0);
 if(!out.length&&Number(cur.bid)>0)out.push({price:Number(cur.bid),seller:"",qty:0});
 return R(out);
}
if(m==="GET"&&p==="/api/market"){
 const z=await Q(e.DB,"SELECT ticker,bid,demand,orders,listed,material FROM order_book WHERE server_id=? ORDER BY bid DESC LIMIT 500",server).all();
 return R((z.results||[]).map(v=>({ticker:v.ticker,ask:Number(v.bid||0),supply:Number(v.listed||0),demand:Number(v.demand||0),orders:Number(v.orders||0),material:v.material})));
}
if(m==="GET"&&p==="/api/flair"){
 const z=await Q(e.DB,"SELECT player AS name,color,tag_id AS tagId,role_id AS roleId FROM player_flair WHERE server_id=?",server).all();
 return R({flair:(z.results||[]).map(v=>({name:v.name,color:v.color||"",tag:v.tagId||"",tagId:v.tagId||"",role:v.roleId||"",roleId:v.roleId||""}))});
}
if(m==="POST"&&p==="/api/flair/set"){
 if(!T(who,32)||!await validUser(who))return R({error:"Verified account required"},403);
 const color=u.searchParams.get("color")||"",tag=u.searchParams.get("tag")||"",role=u.searchParams.get("role")||"";
 if(color.length>16||tag.length>64||role.length>64)return R({error:"Invalid flair"},400);
 await Q(e.DB,"INSERT INTO player_flair(server_id,player,color,tag_id,role_id,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(server_id,player) DO UPDATE SET color=excluded.color,tag_id=excluded.tag_id,role_id=excluded.role_id,updated_at=excluded.updated_at",server,who,color,tag,role,now).run();
 return R({ok:true});
}
if(m==="GET"&&p==="/api/modusers"){
 const z=await Q(e.DB,"SELECT player AS name,seen_at AS lastSeen FROM player_presence WHERE server_id=? AND seen_at>? ORDER BY seen_at DESC LIMIT 500",server,now-2592000000).all();
 const users=(z.results||[]).map(v=>({name:v.name,lastSeen:Number(v.lastSeen),active:now-Number(v.lastSeen)<300000}));
 return R({total:users.length,activeNow:users.filter(v=>v.active).length,users});
}
if(m==="GET"&&p==="/api/partners")return R([]);
if(m==="GET"&&p==="/api/store")return R({buy:"https://www.patreon.com/LifestealMarket",closed:true,rotatesAt:0,items:[]});
if(m==="GET"&&p==="/api/avatar"){
 const name=u.searchParams.get("name")||"";if(!/^[A-Za-z0-9_]{1,16}$/.test(name))return new Response("Invalid player name",{status:400});
 const imageResponse=await fetch("https://mc-heads.net/avatar/"+encodeURIComponent(name)+"/64");
 if(!imageResponse.ok)return new Response("Avatar not found",{status:404});
 return new Response(imageResponse.body,{headers:{"content-type":imageResponse.headers.get("content-type")||"image/png","cache-control":"public, max-age=3600"}});
}
if(m==="GET"&&p==="/api/art")return new Response("No custom artwork stored in this Cloudflare account.",{status:404,headers:{"cache-control":"no-store"}});
return R({error:"Not found"},404)}};