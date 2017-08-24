package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import static io.vertx.core.Future.succeededFuture;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.Response;
import org.apache.commons.io.IOUtils;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.Notification;
import org.folio.rest.jaxrs.model.NotifyCollection;
import org.folio.rest.jaxrs.resource.NotificationsResource;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.PgExceptionUtil;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLQueryValidationException;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.messages.MessageConsts;
import org.folio.rest.tools.messages.Messages;
import org.folio.rest.tools.utils.OutStream;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.tools.utils.ValidationHelper;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;


public class NotificationsResourceImpl implements NotificationsResource {
  private final Logger logger = LoggerFactory.getLogger("okapi");
  private final Messages messages = Messages.getInstance();
  // TODO - Rename the NOTE_somethings to NOTIFY_somethings
  // But only after pasting lots of code from notes
  public static final String NOTE_TABLE = "notify_data";
  private static final String LOCATION_PREFIX = "/notify/";
  private final String idFieldName = "id";
  private static String NOTE_SCHEMA = null;
  private static final String NOTE_SCHEMA_NAME = "apidocs/raml/notify.json";

  private void initCQLValidation() {
    String path = NOTE_SCHEMA_NAME;
    try {
      NOTE_SCHEMA = IOUtils.toString(
        getClass().getClassLoader().getResourceAsStream(path), "UTF-8");
    } catch (Exception e) {
      logger.error("unable to load schema - " + path
        + ", validation of query fields will not be active");
    }
  }

  public NotificationsResourceImpl(Vertx vertx, String tenantId) {
    if (NOTE_SCHEMA == null) {
      //initCQLValidation();  // COmmented out, the validation fails a
      // prerfectly valid query=metaData.createdByUserId=e037b...
    }
    PostgresClient.getInstance(vertx, tenantId).setIdField(idFieldName);
  }

  private CQLWrapper getCQL(String query, int limit, int offset,
    String schema) throws Exception {
    CQL2PgJSON cql2pgJson = null;
    if (schema != null) {
      cql2pgJson = new CQL2PgJSON(NOTE_TABLE + ".jsonb", schema);
    } else {
      cql2pgJson = new CQL2PgJSON(NOTE_TABLE + ".jsonb");
    }
    return new CQLWrapper(cql2pgJson, query)
      .setLimit(new Limit(limit))
      .setOffset(new Offset(offset));
  }


  @Override
  public void getNotify(String query,
    int offset, int limit,
    String lang,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) throws Exception {
    try {
      logger.info("Getting notifications. " + offset + "+" + limit + " q=" + query);
      String tenantId = TenantTool.calculateTenantId(
        okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
      CQLWrapper cql = getCQL(query, limit, offset, NOTE_SCHEMA);

      PostgresClient.getInstance(vertxContext.owner(), tenantId)
        .get(NOTE_TABLE, Notification.class, new String[]{"*"}, cql, true, true,
          reply -> {
            try {
              if (reply.succeeded()) {
                NotifyCollection notes = new NotifyCollection();
                @SuppressWarnings("unchecked")
                List<Notification> notifylist = (List<Notification>) reply.result()[0];
                notes.setNotifications(notifylist);
                notes.setTotalRecords((Integer) reply.result()[1]);
                asyncResultHandler.handle(succeededFuture(
                    GetNotifyResponse.withJsonOK(notes)));
              } else {
                logger.error(reply.cause().getMessage(), reply.cause());
                asyncResultHandler.handle(succeededFuture(GetNotifyResponse                    .withPlainBadRequest(reply.cause().getMessage())));
              }
            } catch (Exception e) {
              logger.error(e.getMessage(), e);
              asyncResultHandler.handle(succeededFuture(GetNotifyResponse                  .withPlainInternalServerError(messages.getMessage(
                      lang, MessageConsts.InternalServerError))));
            }
          });
    } catch (CQLQueryValidationException e1) {
      int start = e1.getMessage().indexOf("'");
      int end = e1.getMessage().lastIndexOf("'");
      String field = e1.getMessage();
      if (start != -1 && end != -1) {
        field = field.substring(start + 1, end);
      }
      Errors e = ValidationHelper.createValidationErrorMessage(field,
        "", e1.getMessage());
      asyncResultHandler.handle(succeededFuture(GetNotifyResponse
        .withJsonUnprocessableEntity(e)));
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      String message = messages.getMessage(lang, MessageConsts.InternalServerError);
      if (e.getCause() != null && e.getCause().getClass().getSimpleName()
        .endsWith("CQLParseException")) {
        message = " CQL parse error " + e.getLocalizedMessage();
      }
      asyncResultHandler.handle(succeededFuture(GetNotifyResponse
        .withPlainInternalServerError(message)));
    }
  }

  @Override
  public void postNotify(String lang,
    Notification entity,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context context)
    throws Exception {
    try {
      String tenantId = TenantTool.calculateTenantId(
        okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
      String id = entity.getId();
      PostgresClient.getInstance(context.owner(), tenantId).save(NOTE_TABLE,
        id, entity,
        reply -> {
          try {
            if (reply.succeeded()) {
              Object ret = reply.result();
              entity.setId((String) ret);
              OutStream stream = new OutStream();
              stream.setData(entity);
              asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                  .withJsonCreated(LOCATION_PREFIX + ret, stream)));
            } else {
              String msg = reply.cause().getMessage();
              if (msg.contains("duplicate key value violates unique constraint")) {
                Errors valErr = ValidationHelper.createValidationErrorMessage(
                  "id", id, "Duplicate id");
                asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                    .withJsonUnprocessableEntity(valErr)));
              } else {
                String error = PgExceptionUtil.badRequestMessage(reply.cause());
                logger.error(msg, reply.cause());
                if (error == null) {
                  asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                      .withPlainInternalServerError(
                        messages.getMessage(lang, MessageConsts.InternalServerError))));
                } else {
                  asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                      .withPlainBadRequest(error)));
                }
              }
            }
          } catch (Exception e) {
            logger.error(e.getMessage(), e);
            asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                .withPlainInternalServerError(
                  messages.getMessage(lang, MessageConsts.InternalServerError))));
          }
        });

    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      asyncResultHandler.handle(
        succeededFuture(PostNotifyResponse.withPlainInternalServerError(
            messages.getMessage(lang, MessageConsts.InternalServerError)))
      );
    }
  }

  @Override
  public void getNotifySelf(String query, int offset, int limit, String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public void postNotifySelf(String lang, Notification entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  @Override
  public void getNotifyById(String id,
    String lang, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context context) throws Exception {
    try {
      if (id.equals("_self")) {
        // The _self endpoint has already handled this request
        return;
      }
      String tenantId = TenantTool.calculateTenantId(
        okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
      Criterion c = new Criterion(
        new Criteria().addField(idFieldName).setJSONB(false)
        .setOperation("=").setValue("'" + id + "'"));

      PostgresClient.getInstance(context.owner(), tenantId)
        .get(NOTE_TABLE, Notification.class, c, true,
          reply -> {
            try {
              if (reply.succeeded()) {
                @SuppressWarnings("unchecked")
                List<Notification> config = (List<Notification>) reply.result()[0];
                if (config.isEmpty()) {
                  asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
                      .withPlainNotFound(id)));
                } else {
                  asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
                      .withJsonOK(config.get(0))));
                }
              } else {
                String error = PgExceptionUtil.badRequestMessage(reply.cause());
                logger.error(error, reply.cause());
                if (error == null) {
                  asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
                      .withPlainInternalServerError(
                        messages.getMessage(lang, MessageConsts.InternalServerError))));
                } else {
                  asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                      .withPlainBadRequest(error)));
                }
              }
            } catch (Exception e) {
              logger.error(e.getMessage(), e);
              asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
                  .withPlainInternalServerError(
                    messages.getMessage(lang, MessageConsts.InternalServerError))));
            }
          });
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      asyncResultHandler.handle(succeededFuture(GetNotifyByIdResponse
        .withPlainInternalServerError(
          messages.getMessage(lang, MessageConsts.InternalServerError))));
    }
  }

  @Override
  public void deleteNotifyById(String id, String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    String tenantId = TenantTool.calculateTenantId(
      okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
    try {
      PostgresClient.getInstance(vertxContext.owner(), tenantId)
        .delete(NOTE_TABLE, id,
          reply -> {
            if (reply.succeeded()) {
              if (reply.result().getUpdated() == 1) {
                asyncResultHandler.handle(succeededFuture(
                    DeleteNotifyByIdResponse.withNoContent()));
              } else {
                logger.error(messages.getMessage(lang,
                    MessageConsts.DeletedCountError, 1, reply.result().getUpdated()));
                asyncResultHandler.handle(succeededFuture(DeleteNotifyByIdResponse
                    .withPlainNotFound(messages.getMessage(lang,
                        MessageConsts.DeletedCountError, 1, reply.result().getUpdated()))));
              }
            } else {
              String error = PgExceptionUtil.badRequestMessage(reply.cause());
              logger.error(error, reply.cause());
              if (error == null) {
                asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                    .withPlainInternalServerError(
                      messages.getMessage(lang, MessageConsts.InternalServerError))));
              } else {
                asyncResultHandler.handle(succeededFuture(PostNotifyResponse
                    .withPlainBadRequest(error)));
              }
            }
          });
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      asyncResultHandler.handle(succeededFuture(DeleteNotifyByIdResponse
        .withPlainInternalServerError(
          messages.getMessage(lang, MessageConsts.InternalServerError))));
    }
  }

  @Override
  public void putNotifyById(String id, String lang,
    Notification entity,
    Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler,
    Context vertxContext) throws Exception {
    try {
      logger.info("PUT notify " + id + " " + Json.encode(entity));
      String noteId = entity.getId();
      if (noteId != null && !noteId.equals(id)) {
        logger.error("Trying to change note Id from " + id + " to " + noteId);
        Errors valErr = ValidationHelper.createValidationErrorMessage("id", noteId,
          "Can not change the id");
        asyncResultHandler.handle(succeededFuture(PutNotifyByIdResponse
          .withJsonUnprocessableEntity(valErr)));
        return;
      }
      String tenantId = TenantTool.calculateTenantId(
        okapiHeaders.get(RestVerticle.OKAPI_HEADER_TENANT));
      PostgresClient.getInstance(vertxContext.owner(), tenantId).update(
        NOTE_TABLE, entity, id,
        reply -> {
          try {
            if (reply.succeeded()) {
              if (reply.result().getUpdated() == 0) {
                asyncResultHandler.handle(succeededFuture(
                    PutNotifyByIdResponse.withPlainInternalServerError(
                      messages.getMessage(lang, MessageConsts.NoRecordsUpdated))));
              } else {
                asyncResultHandler.handle(succeededFuture(
                    PutNotifyByIdResponse.withNoContent()));
              }
            } else {
              logger.error(reply.cause().getMessage());
              asyncResultHandler.handle(succeededFuture(
                  PutNotifyByIdResponse.withPlainInternalServerError(
                    messages.getMessage(lang, MessageConsts.InternalServerError))));
            }
          } catch (Exception e) {
            logger.error(e.getMessage(), e);
            asyncResultHandler.handle(succeededFuture(
                PutNotifyByIdResponse.withPlainInternalServerError(
                  messages.getMessage(lang, MessageConsts.InternalServerError))));
          }
        });
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      asyncResultHandler.handle(succeededFuture(PutNotifyByIdResponse
        .withPlainInternalServerError(
          messages.getMessage(lang, MessageConsts.InternalServerError))));
    }
  }

}