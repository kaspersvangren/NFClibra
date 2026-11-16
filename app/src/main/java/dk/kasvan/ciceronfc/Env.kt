package dk.kasvan.ciceronfc

/**
 * Hvilken Cicero app'en hører til: den rigtige (cicero.systematic.com) eller sandkassen (test).
 * Sættes, når app'en bygges – de to udgaver har hver sit interne navn og kan være installeret samtidig.
 */
object Env {
    val SANDBOX: Boolean = BuildConfig.SANDBOX
    /** Ciceros adresse */
    val CICERO_HOST: String = BuildConfig.CICERO_HOST
    /** Ciceros login-side (Keycloak) */
    val LOGIN_HOST: String = BuildConfig.LOGIN_HOST
    val START_URL: String = "https://$CICERO_HOST/"
    /** Domæner, hvis sider åbnes inde i app'en (Cicero, login, hjælp) */
    val DOMAINS: List<String> = BuildConfig.DOMAINS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    /** Porten, som Cicero kalder RFID-læseren på (sandkassen har sin egen, så de to udgaver ikke støder sammen) */
    val RFID_PORT: Int = BuildConfig.RFID_PORT
    /** Filen i udgivelsen, som netop denne udgave opdaterer sig fra */
    val APK_NAME: String = BuildConfig.APK_NAME
}
