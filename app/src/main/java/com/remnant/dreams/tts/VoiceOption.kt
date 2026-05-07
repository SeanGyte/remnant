package com.remnant.dreams.tts

data class VoiceOption(
    val id: String,
    val friendlyName: String,
    val description: String,
    val languageCode: String,
    val gender: String
) {
    companion object {
        val ALL = listOf(
            VoiceOption("en-AU-Chirp3-HD-Charon", "Jack", "The Early Riser", "en-AU", "Male"),
            VoiceOption("en-AU-Chirp3-HD-Enceladus", "Lachie", "The Surf Report", "en-AU", "Male"),
            VoiceOption("en-AU-News-F", "Ruby", "The Morning Show", "en-AU", "Female"),
            VoiceOption("en-GB-News-K", "James", "The Newsreader", "en-GB", "Male"),
            VoiceOption("en-GB-News-J", "Oliver", "The Gentleman", "en-GB", "Male"),
            VoiceOption("en-GB-News-L", "Hugh", "The Anchor", "en-GB", "Male"),
            VoiceOption("en-GB-News-G", "Charlotte", "The Presenter", "en-GB", "Female"),
            VoiceOption("en-GB-News-I", "Sophie", "The Correspondent", "en-GB", "Female"),
            VoiceOption("en-US-Chirp3-HD-Aoede", "Harper", "The Go-Getter", "en-US", "Female"),
            VoiceOption("en-US-Chirp3-HD-Kore", "Quinn", "The Upbeat", "en-US", "Female"),
        )

        val DEFAULT = ALL[0]

        fun findById(id: String): VoiceOption = ALL.find { it.id == id } ?: DEFAULT
    }
}
