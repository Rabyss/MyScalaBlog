package me.lsbengine.server

import akka.event.LoggingAdapter
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import me.lsbengine.api.PostsAccessor
import me.lsbengine.api.model.{FetchPostResponse, ListActionResponse}
import me.lsbengine.json.JSONSupport
import reactivemongo.api.{DefaultDB, MongoConnection}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

abstract class ServerService(dbConnection: MongoConnection, dbName: String, log: LoggingAdapter)
  extends JSONSupport {

  implicit val twirlMarshaller: ToEntityMarshaller[play.twirl.api.HtmlFormat.Appendable] = Marshaller.opaque { html =>
    HttpEntity(ContentTypes.`text/html(UTF-8)`, html.toString)
  }

  def commonRoutes: Route =
    path("info") {
      get {
        complete(getInfo)
      }
    } ~ pathPrefix("api") {
      pathPrefix("posts") {
        path("list") {
          get {
            ctx => listPosts(ctx)
          }
        } ~ path(IntNumber) {
          id =>
            get {
              ctx => fetchPost(ctx, id)
            }
        }
      }
    }

  val assetsRoutes: Route =
    pathPrefix("css") {
      get {
        getFromResourceDirectory("css")
      }
    } ~ pathPrefix("js") {
      get {
        getFromResourceDirectory("js")
      }
    } ~ pathPrefix("assets") {
      get {
        getFromResourceDirectory("assets")
      }
    }

  def listPosts(requestContext: RequestContext): Future[RouteResult] = {
    log.info(s"[$apiScope] Listing posts.")
    handleWithDb(requestContext) {
      db =>
        val postsAccessor = getPostsAccessor(db)
        postsAccessor.listPosts.flatMap {
          list =>
            requestContext.complete(ListActionResponse(list))
        }
    }
  }

  def fetchPost(requestContext: RequestContext, id: Int): Future[RouteResult] = {
    log.info(s"[$apiScope] Fetching post $id.")
    handleWithDb(requestContext) {
      db =>
        val postsAccessor = getPostsAccessor(db)
        postsAccessor.getPost(id).flatMap {
          case Some(document) => requestContext.complete(FetchPostResponse(document))
          case None => requestContext.complete(NotFound, s"No post found with id: $id.")
        }
    }
  }

  def handleWithDb(requestContext: RequestContext)(handler: DefaultDB => Future[RouteResult]): Future[RouteResult] = {
    dbConnection.database(dbName).flatMap {
      db =>
        handler(db)
    }
  }

  val ownRoutes: Route
  val apiScope: String

  def routes: Route = assetsRoutes ~ commonRoutes ~ ownRoutes

  def getInfo: Map[String, Any] = {
    BuildInfo.toMap + ("repositoryLink" -> BlogConfiguration.repositoryLink) + ("apiScope" -> apiScope)
  }

  def getPostsAccessor(database: DefaultDB): PostsAccessor
}
