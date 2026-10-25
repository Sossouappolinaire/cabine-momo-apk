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

Le workflow attend ces secrets du dépôt GitHub :

- `GOOGLE_SERVICES_JSON_BASE64` : contenu encodé en Base64 du `google-services.json` Firebase du package `com.cabine.momo`.
- `MOMO_KEYSTORE_BASE64` : keystore de signature déjà utilisé pour les mises à jour de cette application, encodé en Base64.
- `MOMO_KEYSTORE_PASSWORD` et `MOMO_KEY_PASSWORD` : mots de passe correspondants.

Ces secrets se configurent dans **Settings → Secrets and variables → Actions** du dépôt. Le fichier de keystore et ses mots de passe ne doivent jamais être commités. Garder le même keystore est nécessaire pour installer une mise à jour par-dessus l’APK déjà distribué.

Le workflow peut aussi lire `firebase/google-services.json` s’il est présent à cet emplacement au moment du build. Il vérifie que le package Firebase est bien `com.cabine.momo`.

L’archive source fournie ne contenait pas le keystore. Pour mettre à jour une installation existante, il faut retrouver **la même clé de signature que celle de l’APK déjà distribué**. Si cette clé est perdue, une nouvelle clé permet seulement une nouvelle installation; Android n’acceptera pas l’APK comme mise à jour de l’ancienne application.
