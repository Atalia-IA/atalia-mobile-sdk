package ai.atalia.chat.internal

import androidx.core.content.FileProvider

/**
 * Subclasse própria para não colidir com o `FileProvider` que o app já declare no manifest.
 * Serve a foto tirada pela câmera antes de ela subir para a conversa.
 */
internal class AtaliaFileProvider : FileProvider()
