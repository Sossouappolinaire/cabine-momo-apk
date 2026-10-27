-- A executer une seule fois dans Supabase > SQL Editor (bouton Run)

-- 1) Ajoute la colonne "ref" (ID / reference de la transaction)
alter table public.operations add column if not exists ref text;

-- 2) Remplit la reference des operations SMS deja envoyees (l'ID se trouve dans leur identifiant)
update public.operations
   set ref = substring(id from 5)
 where id like 'sms-%' and ref is null and substring(id from 5) ~ '[0-9]';

-- 3) Demande a Supabase de recharger sa structure (sinon la colonne est refusee pendant un moment)
notify pgrst, 'reload schema';

-- Verification : doit afficher la colonne ref
select id, type, amount, ref from public.operations order by date desc limit 10;
