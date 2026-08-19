package com.lantern.library.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lantern.library.R

val Playfair = FontFamily(Font(R.font.playfair_regular, FontWeight.Normal))
val Lora = FontFamily(Font(R.font.lora_regular, FontWeight.Normal))
val Merriweather = FontFamily(Font(R.font.merriweather_regular, FontWeight.Normal))
val Garamond = FontFamily(Font(R.font.garamond_regular, FontWeight.Normal))
val Literata = FontFamily(Font(R.font.literata_regular, FontWeight.Normal))
val Inter = FontFamily(Font(R.font.inter_regular, FontWeight.Normal))
val Nunito = FontFamily(Font(R.font.nunito_regular, FontWeight.Normal))
val Baskerville = FontFamily(Font(R.font.baskerville_regular, FontWeight.Normal))
val Atkinson = FontFamily(Font(R.font.atkinson_regular, FontWeight.Normal))
val SourceSerif = FontFamily(Font(R.font.sourceserif_regular, FontWeight.Normal))

val LanternTypography = Typography(
    displaySmall = TextStyle(fontFamily = Playfair, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Playfair, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Playfair, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = Lora, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontSize = 11.sp)
)
