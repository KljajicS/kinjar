package app.kinjar.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable @SerialName("ProjectSaved")
data class ProjectSaved(val project: Project) : Event

@Serializable @SerialName("ProjectCategorySaved")
data class ProjectCategorySaved(val category: ProjectCategory) : Event

@Serializable @SerialName("ProjectCostSaved")
data class ProjectCostSaved(val cost: ProjectCost) : Event

@Serializable @SerialName("ProjectCostRemoved")
data class ProjectCostRemoved(val costId: String, val projectId: String, val transactionId: String? = null) : Event
