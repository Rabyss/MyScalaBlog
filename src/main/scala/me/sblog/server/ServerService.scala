package me.sblog.server

import akka.actor.{Actor, ActorLogging, ActorRefFactory}
import me.sblog.api.PostsWorker
import me.sblog.api.PostsWorker.{FetchDocument, ListAction}
import me.sblog.database.withDb
import org.json4s.{DefaultFormats, Formats}
import reactivemongo.api.{DefaultDB, MongoConnection}
import spray.client.pipelining._
import spray.http.HttpHeaders.Accept
import spray.http.MediaTypes._
import spray.http.StatusCodes.InternalServerError
import spray.http.{HttpRequest, HttpResponse}
import spray.httpx.Json4sSupport
import spray.routing.{HttpService, RequestContext, Route}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

abstract class ServerService(dbConnection: MongoConnection, dbName: String) extends HttpService with Actor with ActorLogging with Json4sSupport {
  override def actorRefFactory: ActorRefFactory = context

  override def receive: Receive = runRoute(commonRoutes ~ routes)

  implicit def json4sFormats: Formats = DefaultFormats

  implicit val pipelineRawJson: HttpRequest => Future[HttpResponse] = (
    addHeader(Accept(`application/json`))
      ~> sendReceive
    )

  val commonRoutes: Route =
    path("info") {
      get {
        complete(getInfo)
      }
    } ~ pathPrefix("posts") {
      path("list") {
        get {
          ctx => listDocuments(ctx)
        }
      } ~ path(IntNumber) {
        id =>
          get {
            ctx => fetchDocument(ctx, id)
          }
      }
    }

  def listDocuments(reqContext: RequestContext): Unit = {
    log.info(s"[$apiScope] Listing posts.")
    handleWithDb(reqContext) {
      (db, ctx) =>
        val documentsWorker = context.actorOf(PostsWorker.props(ctx, db))
        documentsWorker ! ListAction()
    }
  }

  def fetchDocument(reqContext: RequestContext, id: Int): Unit = {
    log.info(s"[$apiScope] Fetching post $id.")
    handleWithDb(reqContext) {
      (db, ctx) =>
        val documentsWorker = context.actorOf(PostsWorker.props(ctx, db))
        documentsWorker ! FetchDocument(id)
    }
  }

  val routes: Route
  val apiScope: String

  def getInfo: Map[String, Any] = {
    BuildInfo.toMap + ("repositoryLink" -> ApplicationConfiguration.repositoryLink) + ("apiScope" -> apiScope)
  }

  protected def handleWithDb(ctx: RequestContext)(handler: (DefaultDB, RequestContext) => Unit): Unit = {
    withDb(dbConnection, dbName) {
      db =>
        handler(db, ctx)
    } {
      e =>
        complete(InternalServerError, s"${e.getMessage}")
    }
  }
}