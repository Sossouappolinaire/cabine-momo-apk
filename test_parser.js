var fs=require('fs');eval(fs.readFileSync(__dirname+'/parser.js','utf8'));
var m1="Transfert 8000F a EBENEZERE MAHOUTIN SALANON(2290156569460) 2026-10-24 19:13:31 Frais: 50F Solde:219F Ref: v ID: 13203308682.Nouvelle App MoMo https://onelink.to/va7cnt";
var m2="Depot recu 1000F de DE STYGO  GEST 1 (2290146255197 - RBCCM/PKO/ 23 A 18209) le 2026-10-20 19:29:01 Solde:1003F ID:13171504285Frais:0F.Nouvelle App MoMo https://onelink.to/va7cnt";
var m3="Bonjour, rendez-vous demain à 10h";
var m4="Vous avez achete un forfait internet 2Go pour 1000F. ID: 99887766";
[m1,m2,m3,m4].forEach(function(m){console.log(JSON.stringify(smsParse(m,"MoMo")));});
