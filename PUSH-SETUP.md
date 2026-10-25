# Notifications et sécurité Android — Cabine Momo

## Fonctionnement

- Le PIN est conservé sur le téléphone Android, chiffré avec le Keystore Android. Il protège l’ouverture de l’application et se verrouille après 30 secondes en arrière-plan.
- L’empreinte ou une autre biométrie forte peut être activée dans **Paramètres**; le PIN reste le moyen de secours.
- Seuls les comptes dont `app_metadata.role` vaut `admin` enregistrent un appareil pour les notifications push.
- Un trigger Supabase détecte les changements de mot de passe et les changements d’adresse e-mail effectivement enregistrés pour un compte `owner`. Un changement d’e-mail en attente n’est pas annoncé avant sa validation.
- La notification contient le nom de l’entreprise et le type de changement; elle ne contient jamais le mot de passe ni la nouvelle adresse e-mail.

## Activer le serveur push Supabase

1. Dans le projet Supabase qui héberge déjà Cabine Momo, appliquer la migration :

   ```sh
   supabase link --project-ref VOTRE_REFERENCE
   supabase db push
   ```

2. Dans Firebase Console, créer ou sélectionner le projet Firebase Android avec le package `com.cabine.momo`, activer Firebase Cloud Messaging et créer un compte de service autorisé à envoyer des messages FCM.
3. Dans **Supabase → Edge Functions → Secrets**, configurer `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL` et `FCM_PRIVATE_KEY` à partir de ce compte de service. Ces valeurs sont des secrets serveur : ne pas les mettre dans le code, dans le dépôt ou dans une conversation.
4. Déployer la fonction :

   ```sh
   supabase functions deploy admin-push
   ```

5. Dans **Supabase → Database → Webhooks**, créer un webhook `INSERT` sur `public.cabine_security_events`, dirigé vers la fonction `admin-push`. Ajouter l’en-tête d’autorisation avec la clé de service via l’option sécurisée du tableau de bord; ne pas inscrire cette clé dans le code.
6. Installer l’APK sur le téléphone administrateur, se connecter au compte admin et autoriser les notifications Android. L’appareil est alors enregistré automatiquement.

La migration conserve uniquement l’identifiant du propriétaire, son nom d’entreprise, le type d’événement et l’heure; elle ne conserve aucune donnée de mot de passe ou d’adresse e-mail.

## Construire l’APK depuis GitHub Actions

Même principe que la version précédente : déposez le contenu de ce dossier dans le dépôt GitHub (en gardant `momo.keystore` et `make_android_assets.py` déjà présents à la racine), puis lancez le workflow. La signature et l’icône sont gérées automatiquement; aucun secret de signature n’est à configurer.

Seule la configuration Firebase est nécessaire pour les notifications : ajoutez `firebase/google-services.json` (package `com.cabine.momo`) au dépôt, ou le secret GitHub `GOOGLE_SERVICES_JSON_BASE64`.
