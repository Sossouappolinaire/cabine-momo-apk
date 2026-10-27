/* ===== Lecture automatique des SMS MoMo ===== */
/* Réglage : quel type d'opération pour quel début de SMS. Modifiable ici. */
var SMS_RULES=[
  {re:/^\s*transfert\b/i,type:"dep"},
  {re:/^\s*d[ée]p[ôo]t\s+re[çc]u/i,type:"ret"},
  {re:/^\s*retrait\b/i,type:"ret"}
];
function smsNum(s){var n=parseInt(String(s||"").replace(/[^\d]/g,""),10);return isNaN(n)?null:n}
function smsSim(sender,body){var t=(sender||"")+" "+(body||"");
  if(/celtis/i.test(t))return"Celtis";if(/moov/i.test(t))return"Moov";if(/mtn|momo/i.test(t))return"MTN";return""}
/* Référence / ID de la transaction : « ID: 13203308682 », ou « Ref: MP2410.1234 » (au moins un chiffre) */
function smsRef(b){var m=b.match(/\bID\s*(?:de\s+transaction)?\s*:?\s*(\d{6,})/i)||b.match(/\b(?:ID|r[ée]f(?:[ée]rence)?)\s*:?\s*([A-Za-z]{0,4}\d[A-Za-z0-9._-]{4,})/i);
  return m?m[1].replace(/[._-]+$/,""):""}
function smsParse(body,sender){
  var b=String(body||"").replace(/\s+/g," ").trim();if(!b)return null;
  var type="";
  for(var i=0;i<SMS_RULES.length;i++){if(SMS_RULES[i].re.test(b)){type=SMS_RULES[i].type;break}}
  if(!type){
    if(/forfait|pass\b|bundle/i.test(b)&&/internet|data|\bgo\b|\bmo\b/i.test(b))type="fnet";
    else if(/forfait/i.test(b)&&/appel|voix|minute/i.test(b))type="fapp";
    else if(/cr[ée]dit|recharge|airtime/i.test(b))type="cred";
  }
  var am=b.match(/(\d[\d\s.]*)\s*F(?:CFA)?\b/i),amount=am?smsNum(am[1]):null;
  var fm=b.match(/frais\s*:?\s*(\d[\d\s.]*)\s*F/i),frais=fm?smsNum(fm[1]):0;
  var txid=smsRef(b);
  var dm=b.match(/(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})/),date="",at="";
  if(dm){date=dm[1];var d=new Date(dm[1]+"T"+dm[2]);at=isNaN(d)?"":d.toISOString()}
  return{type:type,amount:amount,frais:frais||0,txid:txid,date:date,at:at,sim:smsSim(sender,b),raw:b};
}
