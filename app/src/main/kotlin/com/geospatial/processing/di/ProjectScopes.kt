package com.geospatial.processing.di

import com.geospatial.processing.data.database.ProjectSession
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback
import java.io.Closeable
import java.io.File

/** Qualifier of the Koin scope that lives exactly as long as one open project. */
val PROJECT_SCOPE = named("project")

/**
 * A project that is open: its folder and the Koin scope holding its [ProjectSession], repository,
 * services and WorkbenchViewModel. [close] closes the scope, which closes the database.
 */
class OpenProject(
    val projectDir: File,
    /** Null only in tests that don't use Koin. */
    val scope: Scope?,
    private val onClose: () -> Unit,
) : Closeable {
    override fun close() = onClose()
}

fun interface ProjectOpener {
    /** Opens the project's database. Throws if the database cannot be opened. */
    fun open(projectDir: File): OpenProject
}

/** Production [ProjectOpener]: one Koin scope per project, with the session declared into it. */
class KoinProjectOpener(private val koin: Koin) : ProjectOpener {

    override fun open(projectDir: File): OpenProject {
        val session = ProjectSession.open(projectDir)
        // Unique id per opening: re-opening the same project later gets a fresh scope.
        val scope = koin.createScope("project:${projectDir.canonicalPath}:${System.nanoTime()}", PROJECT_SCOPE)
        scope.declare(session)
        scope.registerCallback(object : ScopeCallback {
            override fun onScopeClose(scope: Scope) = session.close()
        })
        return OpenProject(projectDir, scope) { if (!scope.closed) scope.close() }
    }
}
