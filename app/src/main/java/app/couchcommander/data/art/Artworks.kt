package app.couchcommander.data.art

import java.net.URLEncoder

/** One painting. [file] is its file name on Wikimedia Commons. */
data class Artwork(val file: String, val title: String, val artist: String, val year: String)

/**
 * The curated catalogue. Every entry is a public-domain work whose Commons file was checked to exist and be marked
 * "Public domain" (2026-10-16), and is at least 1350 px on its short side so it stays sharp on a 1080p screen.
 * Add only works whose artist died more than 70 years ago and that were published before 1931, so they are public
 * domain in both the EU and the US.
 */
object Artworks {
    val all = listOf(
        Artwork("Tsunami_by_hokusai_19th_century.jpg", "Under the Wave off Kanagawa", "Katsushika Hokusai", "c. 1830–32"),
        Artwork("Red_Fuji_southern_wind_clear_morning.jpg", "Fine Wind, Clear Morning", "Katsushika Hokusai", "c. 1830–32"),
        Artwork("Van_Gogh_-_Starry_Night_-_Google_Art_Project.jpg", "The Starry Night", "Vincent van Gogh", "1889"),
        Artwork("A_Sunday_on_La_Grande_Jatte,_Georges_Seurat,_1884.jpg", "A Sunday on La Grande Jatte", "Georges Seurat", "1884–86"),
        Artwork("Claude_Monet_-_The_Beach_at_Sainte-Adresse_-_Google_Art_Project.jpg", "The Beach at Sainte-Adresse", "Claude Monet", "1867"),
        Artwork("Gustav_Klimt_016.jpg", "The Kiss", "Gustav Klimt", "1907–08"),
        Artwork("Claude_Monet,_Impression,_soleil_levant.jpg", "Impression, Sunrise", "Claude Monet", "1872"),
        Artwork("Vincent_van_Gogh_-_Wheatfield_with_crows_-_Google_Art_Project.jpg", "Wheatfield with Crows", "Vincent van Gogh", "1890"),
        Artwork("Claude_Monet_-_Water_Lilies_-_1906,_Ryerson.jpg", "Water Lilies", "Claude Monet", "1906"),
        Artwork("Albert_Bierstadt_-_Among_the_Sierra_Nevada,_California_-_Google_Art_Project.jpg", "Among the Sierra Nevada, California", "Albert Bierstadt", "1868"),
        Artwork("Albert_Bierstadt_-_The_Rocky_Mountains,_Lander's_Peak.jpg", "The Rocky Mountains, Lander's Peak", "Albert Bierstadt", "1863"),
        Artwork("Cole_Thomas_The_Oxbow_(The_Connecticut_River_near_Northampton_1836).jpg", "The Oxbow", "Thomas Cole", "1836"),
        Artwork("Hovhannes_Aivazovsky_-_The_Ninth_Wave_-_Google_Art_Project.jpg", "The Ninth Wave", "Ivan Aivazovsky", "1850"),
        Artwork("Baigneurs_a_Asnieres.jpg", "Bathers at Asnières", "Georges Seurat", "1884"),
        Artwork("Henri_Rousseau_-_Le_Rêve_-_Google_Art_Project.jpg", "The Dream", "Henri Rousseau", "1910"),
        Artwork("Claude_Monet_-_Poppy_Field_-_Google_Art_Project.jpg", "Poppy Field", "Claude Monet", "1873"),
        Artwork("Pierre-Auguste_Renoir_-_Luncheon_of_the_Boating_Party_-_Google_Art_Project.jpg", "Luncheon of the Boating Party", "Pierre-Auguste Renoir", "1880–81"),
        Artwork("John_Constable_The_Hay_Wain.jpg", "The Hay Wain", "John Constable", "1821"),
        Artwork("Vincent_van_Gogh_-_Almond_blossom_-_Google_Art_Project.jpg", "Almond Blossom", "Vincent van Gogh", "1890"),
        Artwork("Edvard_Munch_-_The_Sun_-_Google_Art_Project.jpg", "The Sun", "Edvard Munch", "1911"),
        Artwork("Paul_Gauguin_-_D'ou_venons-nous.jpg", "Where Do We Come From? What Are We? Where Are We Going?", "Paul Gauguin", "1897–98"),
        Artwork("The_Fighting_Temeraire,_JMW_Turner,_National_Gallery.jpg", "The Fighting Temeraire", "J. M. W. Turner", "1839"),
        Artwork("Turner_-_Rain,_Steam_and_Speed_-_National_Gallery_file.jpg", "Rain, Steam and Speed", "J. M. W. Turner", "1844"),
        Artwork("Arkhip_Kuindzhi_-_Ночь_на_Днепре_-_Google_Art_Project.jpg", "Moonlit Night on the Dnieper", "Arkhip Kuindzhi", "1880"),
        Artwork("Twilight_in_the_Wilderness_by_Frederic_Edwin_Church_(3).jpg", "Twilight in the Wilderness", "Frederic Edwin Church", "1860"),
        Artwork("Claude_Monet_-_The_Houses_of_Parliament,_Sunset.jpg", "The Houses of Parliament, Sunset", "Claude Monet", "1903"),
        Artwork("Joaquín_Sorolla_y_Bastida_-_Strolling_along_the_Seashore_-_Google_Art_Project.jpg", "Strolling along the Seashore", "Joaquín Sorolla", "1909"),
        Artwork("Marc,_Franz_-_Blue_Horse_I_-_Google_Art_Project.jpg", "Blue Horse I", "Franz Marc", "1911"),
        Artwork("Hilma_af_Klint_-_The_Ten_Largest_No._3_-_Youth_-_1907.jpg", "The Ten Largest, No. 3, Youth", "Hilma af Klint", "1907"),
    )

    /** Bundled copy of the Great Wave, shown until the first download finishes or when the TV is offline. */
    val fallback = all.first()

    /** Commons serves a scaled copy through Special:FilePath, so the TV never downloads a 40 000 px original. */
    fun url(artwork: Artwork, width: Int = 1920): String =
        "https://commons.wikimedia.org/wiki/Special:FilePath/" +
            URLEncoder.encode(artwork.file, "UTF-8").replace("+", "%20") + "?width=$width"
}
