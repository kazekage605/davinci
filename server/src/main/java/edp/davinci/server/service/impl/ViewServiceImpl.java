/*
 * <<
 *  Davinci
 *  ==
 *  Copyright (C) 2016 - 2019 EDP
 *  ==
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *        http://www.apache.org/licenses/LICENSE-2.0
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  >>
 *
 */

package edp.davinci.server.service.impl;

import edp.davinci.commons.util.JSONUtils;
import edp.davinci.commons.util.MD5Utils;
import edp.davinci.commons.util.StringUtils;
import edp.davinci.core.dao.entity.RelRoleView;
import edp.davinci.core.dao.entity.Source;
import edp.davinci.core.dao.entity.User;
import edp.davinci.core.dao.entity.View;
import edp.davinci.server.commons.Constants;
import edp.davinci.server.component.excel.SQLContext;
import edp.davinci.server.dao.RelRoleViewExtendMapper;
import edp.davinci.server.dao.SourceExtendMapper;
import edp.davinci.server.dao.ViewExtendMapper;
import edp.davinci.server.dao.WidgetExtendMapper;
import edp.davinci.server.dto.project.ProjectDetail;
import edp.davinci.server.dto.project.ProjectPermission;
import edp.davinci.server.dto.source.SourceBaseInfo;
import edp.davinci.server.dto.view.*;
import edp.davinci.server.enums.CheckEntityEnum;
import edp.davinci.server.enums.LogNameEnum;
import edp.davinci.server.enums.SqlVariableTypeEnum;
import edp.davinci.server.enums.SqlVariableValueTypeEnum;
import edp.davinci.server.enums.UserPermissionEnum;
import edp.davinci.server.exception.NotFoundException;
import edp.davinci.server.exception.ServerException;
import edp.davinci.server.exception.UnAuthorizedExecption;
import edp.davinci.server.model.*;
import edp.davinci.server.service.ProjectService;
import edp.davinci.server.service.ViewService;
import edp.davinci.server.util.BaseLock;
import edp.davinci.commons.util.CollectionUtils;
import edp.davinci.server.util.RedisUtils;
import edp.davinci.server.util.SourceUtils;
import edp.davinci.server.util.SqlParseUtils;
import edp.davinci.server.util.SqlUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupFile;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static edp.davinci.server.commons.Constants.NO_AUTH_PERMISSION;
import static edp.davinci.server.enums.SqlVariableTypeEnum.AUTHVARE;
import static edp.davinci.server.enums.SqlVariableTypeEnum.QUERYVAR;
import static edp.davinci.server.commons.Constants.COMMA;
import static edp.davinci.server.commons.Constants.MINUS;

@Slf4j
@Service("viewService")
public class ViewServiceImpl extends BaseEntityService implements ViewService {

    private static final Logger optLogger = LoggerFactory.getLogger(LogNameEnum.BUSINESS_OPERATION.getName());

    @Autowired
    private ViewExtendMapper viewExtendMapper;

    @Autowired
    private SourceExtendMapper sourceExtendMapper;

    @Autowired
    private WidgetExtendMapper widgetMapper;

    @Autowired
    private RelRoleViewExtendMapper relRoleViewExtendMapper;

    @Autowired
    private SqlUtils sqlUtils;

    @Autowired
    private RedisUtils redisUtils;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private SqlParseUtils sqlParseUtils;

    @Value("${sql_template_delimiter:$}")
    private String sqlTempDelimiter;

    private static final String SQL_VARABLE_KEY = "name";
    
    private static final CheckEntityEnum entity = CheckEntityEnum.VIEW;
    
    private static final ExecutorService roleParamThreadPool = Executors.newFixedThreadPool(8);

    @Override
    public boolean isExist(String name, Long id, Long projectId) {
        Long viewId = viewExtendMapper.getByNameWithProjectId(name, projectId);
        if (null != id && null != viewId) {
            return !id.equals(viewId);
        }
        return null != viewId && viewId.longValue() > 0L;
    }

    /**
     * 获取View列表
     *
     * @param projectId
     * @param user
     * @return
     */
    @Override
    public List<ViewBaseInfo> getViews(Long projectId, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

        ProjectDetail projectDetail = null;
        try {
            projectDetail = projectService.getProjectDetail(projectId, user, false);
        } catch (UnAuthorizedExecption e) {
            return null;
        }

        List<ViewBaseInfo> views = viewExtendMapper.getViewBaseInfoByProject(projectId);
        if (null == views) {
            return null;
        }

        if (isHiddenPermission(projectDetail, user)) {
        	return null;
        }
        
        return views;
    }
    
	private boolean isHiddenPermission(ProjectDetail projectDetail, User user) {
		ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
		return projectPermission.getVizPermission() == UserPermissionEnum.HIDDEN.getPermission()
				&& projectPermission.getWidgetPermission() == UserPermissionEnum.HIDDEN.getPermission()
				&& projectPermission.getViewPermission() == UserPermissionEnum.HIDDEN.getPermission();
	}

    @Override
    public ViewWithSourceBaseInfo getView(Long id, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {
        ViewWithSourceBaseInfo view = viewExtendMapper.getViewWithSourceBaseInfo(id);
        if (null == view) {
            throw new NotFoundException("View is not found");
        }

        ProjectDetail projectDetail = projectService.getProjectDetail(view.getProjectId(), user, false);
        if (isHiddenPermission(projectDetail, user)) {
        	throw new UnAuthorizedExecption("Insufficient permissions");
        }

        List<RelRoleView> relRoleViews = relRoleViewExtendMapper.getByView(view.getId());
        view.setRoles(relRoleViews);
        return view;
    }

    @Override
    public SQLContext getSQLContext(boolean isMaintainer, ViewWithSource viewWithSource, ViewExecuteParam executeParam, User user) {
        
    	if (null == executeParam || (CollectionUtils.isEmpty(executeParam.getGroups()) && CollectionUtils.isEmpty(executeParam.getAggregators()))) {
            return null;
        }
        
    	Source source = viewWithSource.getSource();
    	if (null == source) {
            throw new NotFoundException("Source is not found");
        }
        
    	String sql = viewWithSource.getSql();
    	if (StringUtils.isEmpty(sql)) {
            throw new NotFoundException("Sql is not found");
        }

        SQLContext context = new SQLContext();
        //解析变量
        List<SqlVariable> variables = SqlParseUtils.getVariables(viewWithSource.getVariable(), viewWithSource.getSql());
        //解析sql
        SqlEntity sqlEntity = sqlParseUtils.parseSql(sql, variables, sqlTempDelimiter, user, isMaintainer);
        //列权限（只记录被限制访问的字段）
        Set<String> excludeColumns = new HashSet<>();

        packageParams(isMaintainer, viewWithSource.getId(), sqlEntity, variables, executeParam.getParams(), excludeColumns, user);

        String srcSql = sqlParseUtils.replaceParams(sqlEntity.getSql(), sqlEntity.getQuaryParams(), sqlEntity.getAuthParams(), sqlTempDelimiter);
        context.setExecuteSql(sqlParseUtils.getSqls(srcSql, Boolean.FALSE));

        List<String> querySqlList = sqlParseUtils.getSqls(srcSql, Boolean.TRUE);
        if (!CollectionUtils.isEmpty(querySqlList)) {
            buildQuerySql(querySqlList, source, executeParam);
            String config = source.getConfig();
            executeParam.addExcludeColumn(excludeColumns, SourceUtils.getJdbcUrl(config), SourceUtils.getDbVersion(config));
            context.setQuerySql(querySqlList);
            context.setViewExecuteParam(executeParam);
        }

        if (!CollectionUtils.isEmpty(excludeColumns)) {
            List<String> excludeList = excludeColumns.stream().collect(Collectors.toList());
            context.setExcludeColumns(excludeList);
        }
        
        return context;
    }

    /**
     * 新建View
     *
     * @param viewCreate
     * @param user
     * @return
     */
    @Override
    @Transactional
    public ViewWithSourceBaseInfo createView(ViewCreate viewCreate, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {
        
		Long projectId = viewCreate.getProjectId();
		checkWritePermission(entity, projectId, user, "create");

		String name = viewCreate.getName();
		if (isExist(name, null, projectId)) {
			alertNameTaken(entity, name);
		}

		Long sourceId = viewCreate.getSourceId();
		Source source = getSource(sourceId);

		// 测试连接
		if (!sqlUtils.init(source).testConnection()) {
			throw new ServerException("Get source connection fail");
		}

		BaseLock lock = getLock(entity, name, projectId);
		if (lock != null && !lock.getLock()) {
			alertNameTaken(entity, name);
		}

		try {
			View view = new View();
			view.setCreateBy(user.getId());
			view.setCreateTime(new Date());
			BeanUtils.copyProperties(viewCreate, view);
			if (viewExtendMapper.insert(view) <= 0) {
				throw new ServerException("Create view fail");
			}
			
			optLogger.info("View({}) is create by user({})", view.getId(), user.getId());
			
			if (!CollectionUtils.isEmpty(viewCreate.getRoles()) && !StringUtils.isEmpty(viewCreate.getVariable())) {
				checkAndInsertRoleParam(viewCreate.getVariable(), viewCreate.getRoles(), user, view);
			}

			SourceBaseInfo sourceBaseInfo = new SourceBaseInfo();
			BeanUtils.copyProperties(source, sourceBaseInfo);

			ViewWithSourceBaseInfo viewWithSource = new ViewWithSourceBaseInfo();
			BeanUtils.copyProperties(view, viewWithSource);
			
			viewWithSource.setSource(sourceBaseInfo);
			return viewWithSource;
		} finally {
			releaseLock(lock);
		}
    }

    private Source getSource(Long id) {
    	Source source = sourceExtendMapper.selectByPrimaryKey(id);
        if (null == source) {
            log.error("Source({}) not found", id);
            throw new NotFoundException("Source is not found");
        }
        return source;
    }

    /**
     * 更新View
     *
     * @param viewUpdate
     * @param user
     * @return
     */
    @Override
    @Transactional
    public boolean updateView(ViewUpdate viewUpdate, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

    	Long id = viewUpdate.getId();
        View view = getView(id);

        Long projectId = view.getProjectId();
        checkWritePermission(entity, projectId, user, "update");

        String name = viewUpdate.getName();
        if (isExist(name, id, projectId)) {
            alertNameTaken(entity, name);
        }

        Source source = getSource(view.getSourceId());

        //测试连接
        if (!sqlUtils.init(source).testConnection()) {
            throw new ServerException("Get source connection fail");
        }
        
		BaseLock lock = getLock(entity, name, projectId);
		if (lock != null && !lock.getLock()) {
			alertNameTaken(entity, name);
		}
		
		try {
			
			String originStr = view.toString();
	        BeanUtils.copyProperties(viewUpdate, view);
	        view.setUpdateBy(user.getId());
	        view.setUpdateTime(new Date());

	        if (viewExtendMapper.update(view) <= 0) {
	            throw new ServerException("Update view fail");
	        }
	        
	        optLogger.info("View({}) is updated by user({}), origin:{}", view.getId(), user.getId(), originStr);
            
	        if (CollectionUtils.isEmpty(viewUpdate.getRoles())) {
                relRoleViewExtendMapper.deleteByViewId(id);
            }
	        
	        if (!StringUtils.isEmpty(viewUpdate.getVariable())) {
                checkAndInsertRoleParam(viewUpdate.getVariable(), viewUpdate.getRoles(), user, view);
            }

	        return true;
			
		}finally {
			releaseLock(lock);
		}
    }

    private View getView(Long id) {
    	 View view = viewExtendMapper.selectByPrimaryKey(id);
         if (null == view) {
        	 log.error("View({}) not found", id);
             throw new NotFoundException("View is not found");
         }
         return view;
    }
    
    /**
     * 删除View
     *
     * @param id
     * @param user
     * @return
     */
    @Override
    @Transactional
    public boolean deleteView(Long id, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

        View view = getView(id);

        ProjectDetail projectDetail = null;
        try {
            projectDetail = projectService.getProjectDetail(view.getProjectId(), user, false);
        } catch (UnAuthorizedExecption e) {
        	alertUnAuthorized(entity, user, "delete");
        }

        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
        if (projectPermission.getViewPermission() < UserPermissionEnum.DELETE.getPermission()) {
        	alertUnAuthorized(entity, user, "delete");
        }

        if (!CollectionUtils.isEmpty(widgetMapper.getWidgetsByWiew(id))) {
            throw new ServerException("The current view has been referenced, please delete the reference and then operate");
        }

        if (viewExtendMapper.deleteByPrimaryKey(id) <= 0) {
        	throw new ServerException("Delete view fail");
        }
        
        optLogger.info("View({}) is delete by user({})", view.getId(), user.getId());
        relRoleViewExtendMapper.deleteByViewId(id);
        return true;
    }


    /**
     * 执行sql
     *
     * @param executeSql
     * @param user
     * @return
     */
    @Override
    public PaginateWithQueryColumns executeSql(ViewExecuteSql executeSql, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

        Source source = getSource(executeSql.getSourceId());
        
        ProjectDetail projectDetail = null;
        try {
            projectDetail = projectService.getProjectDetail(source.getProjectId(), user, false);
        } catch (UnAuthorizedExecption e) {
            throw new UnAuthorizedExecption("You have not permission to execute sql");
        }

        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
        if (projectPermission.getSourcePermission() == UserPermissionEnum.HIDDEN.getPermission()
                || projectPermission.getViewPermission() < UserPermissionEnum.WRITE.getPermission()) {
            throw new UnAuthorizedExecption("You have not permission to execute sql");
        }

        //结构化Sql
        PaginateWithQueryColumns paginateWithQueryColumns = null;
        try {
            SqlEntity sqlEntity = sqlParseUtils.parseSql(executeSql.getSql(), executeSql.getVariables(), sqlTempDelimiter, user, true);
            if (null == sqlUtils || null == sqlEntity || StringUtils.isEmpty(sqlEntity.getSql())) {
                return paginateWithQueryColumns;
            }

            if (isMaintainer(user, projectDetail)) {
				sqlEntity.setAuthParams(null);
			}

			if (!CollectionUtils.isEmpty(sqlEntity.getQuaryParams())) {
				sqlEntity.getQuaryParams().forEach((k, v) -> {
					if (v instanceof List && ((List) v).size() > 0) {
						v = ((List) v).stream().collect(Collectors.joining(COMMA)).toString();
					}
					sqlEntity.getQuaryParams().put(k, v);
				});
			}

			String srcSql = sqlParseUtils.replaceParams(sqlEntity.getSql(), sqlEntity.getQuaryParams(),
					sqlEntity.getAuthParams(), sqlTempDelimiter);

			SqlUtils sqlUtils = this.sqlUtils.init(source);

			List<String> executeSqlList = sqlParseUtils.getSqls(srcSql, false);

			List<String> querySqlList = sqlParseUtils.getSqls(srcSql, true);

			if (!CollectionUtils.isEmpty(executeSqlList)) {
				executeSqlList.forEach(sql -> sqlUtils.execute(sql));
			}
			
			if (!CollectionUtils.isEmpty(querySqlList)) {
				for (String sql : querySqlList) {
					sql = SqlParseUtils.rebuildSqlWithFragment(sql);
					paginateWithQueryColumns = sqlUtils.syncQuery4Paginate(sql, null, null, null, executeSql.getLimit(),
							null);
				}
			}

        } catch (Exception e) {
        	log.error(e.getMessage(), e);
            throw new ServerException(e.getMessage());
        }

        return paginateWithQueryColumns;
    }

    private boolean isMaintainer(User user, ProjectDetail projectDetail) {
        return projectService.isMaintainer(projectDetail, user);
    }

    /**
     * 返回view源数据集
     *
     * @param id
     * @param executeParam
     * @param user
     * @return
     */
    @Override
    public Paginate<Map<String, Object>> getData(Long id, ViewExecuteParam executeParam, User user) throws NotFoundException, UnAuthorizedExecption, ServerException, SQLException {

    	if (null == executeParam || (CollectionUtils.isEmpty(executeParam.getGroups()) && CollectionUtils.isEmpty(executeParam.getAggregators()))) {
            return null;
        }

        ViewWithSource viewWithSource = getViewWithSource(id);
        ProjectDetail projectDetail = projectService.getProjectDetail(viewWithSource.getProjectId(), user, false);
        if (!projectService.allowGetData(projectDetail, user)) {
            alertUnAuthorized(entity, user, "get data for");
        }

        return getResultDataList(projectService.isMaintainer(projectDetail, user), viewWithSource, executeParam, user);
    }

    private ViewWithSource getViewWithSource(Long id) {
    	 ViewWithSource viewWithSource = viewExtendMapper.getViewWithSource(id);
         if (null == viewWithSource) {
             log.info("View({}) not found", id);
             throw new NotFoundException("View is not found");
         }
         return viewWithSource;
    }

    public void buildQuerySql(List<String> querySqlList, Source source, ViewExecuteParam executeParam) {
		if (null == executeParam) {
			return;
		}
		
		String config = source.getConfig();
		String url = SourceUtils.getJdbcUrl(config);
		String version = SourceUtils.getDbVersion(config);

		// 构造参数， 原有的被传入的替换
		STGroup stg = new STGroupFile(Constants.SQL_TEMPLATE);
		ST st = stg.getInstanceOf("querySql");
		st.add("nativeQuery", executeParam.isNativeQuery());
		st.add("groups", executeParam.getGroups());

		if (executeParam.isNativeQuery()) {
			st.add("aggregators", executeParam.getAggregators());
		} else {
			st.add("aggregators", executeParam.getAggregators(url, version));
		}
		st.add("orders", executeParam.getOrders(url, version));
		st.add("filters", convertFilters(executeParam.getFilters(), source));
		st.add("keywordPrefix", SqlUtils.getKeywordPrefix(url, version));
		st.add("keywordSuffix", SqlUtils.getKeywordSuffix(url, version));

		for (int i = 0; i < querySqlList.size(); i++) {
			st.add("sql", querySqlList.get(i));
			querySqlList.set(i, st.render());
		}
    }

	public List<String> convertFilters(List<String> filterStrs, Source source) {
		List<String> whereClauses = new ArrayList<>();
		List<SqlFilter> filters = new ArrayList<>();
		try {
			if (null == filterStrs || filterStrs.isEmpty()) {
				return null;
			}

			for (String str : filterStrs) {
				SqlFilter obj = JSONUtils.toObject(str, SqlFilter.class);
				if (!StringUtils.isEmpty(obj.getName())) {
					String config = source.getConfig();
					obj.setName(ViewExecuteParam.getField(obj.getName(), SourceUtils.getJdbcUrl(config), SourceUtils.getDbVersion(config)));
				}
				filters.add(obj);
			}
			filters.forEach(filter -> whereClauses.add(SqlFilter.dealFilter(filter)));

		} catch (Exception e) {
			log.error("Convert filters error, filterStrs={}, source={}, filters={} , whereClauses={}",
					JSONUtils.toString(filterStrs), JSONUtils.toString(source), JSONUtils.toString(filters), JSONUtils.toString(whereClauses));
			throw e;
		}
		return whereClauses;
	}


    /**
     * 获取结果集
     *
     * @param isMaintainer
     * @param viewWithSource
     * @param executeParam
     * @param user
     * @return
     * @throws ServerException
     */
    @Override
    public PaginateWithQueryColumns getResultDataList(boolean isMaintainer, ViewWithSource viewWithSource, ViewExecuteParam executeParam, User user) throws ServerException, SQLException {

    	PaginateWithQueryColumns paginate = null;

        if (null == executeParam || (CollectionUtils.isEmpty(executeParam.getGroups()) && CollectionUtils.isEmpty(executeParam.getAggregators()))) {
            return null;
        }

        if (null == viewWithSource.getSource()) {
            throw new NotFoundException("Source is not found");
        }

		String cacheKey = null;
		boolean withCache = null != executeParam && null != executeParam.getCache() && executeParam.getCache() && executeParam.getExpired() > 0L;

		try {

			if (StringUtils.isEmpty(viewWithSource.getSql())) {
				return paginate;
			}

			// 解析变量
			List<SqlVariable> variables = SqlParseUtils.getVariables(viewWithSource.getVariable(), viewWithSource.getSql());
			// 解析sql
			SqlEntity sqlEntity = sqlParseUtils.parseSql(viewWithSource.getSql(), variables, sqlTempDelimiter, user, isMaintainer);
			// 列权限（只记录被限制访问的字段）
			Set<String> excludeColumns = new HashSet<>();
			packageParams(isMaintainer, viewWithSource.getId(), sqlEntity, variables, executeParam.getParams(),
					excludeColumns, user);
			String srcSql = sqlParseUtils.replaceParams(sqlEntity.getSql(), sqlEntity.getQuaryParams(),
					sqlEntity.getAuthParams(), sqlTempDelimiter);

			Source source = viewWithSource.getSource();

			SqlUtils sqlUtils = this.sqlUtils.init(source);

			List<String> executeSqlList = sqlParseUtils.getSqls(srcSql, false);
			if (!CollectionUtils.isEmpty(executeSqlList)) {
				executeSqlList.forEach(sql -> sqlUtils.execute(sql));
			}

			List<String> querySqlList = sqlParseUtils.getSqls(srcSql, true);
			if (!CollectionUtils.isEmpty(querySqlList)) {
				buildQuerySql(querySqlList, source, executeParam);
				String config = source.getConfig();
				executeParam.addExcludeColumn(excludeColumns, SourceUtils.getJdbcUrl(config), SourceUtils.getDbVersion(config));
				
				if (withCache) {

					StringBuilder slatBuilder = new StringBuilder();
					slatBuilder.append(executeParam.getPageNo());
					slatBuilder.append(MINUS);
					slatBuilder.append(executeParam.getLimit());
					slatBuilder.append(MINUS);
					slatBuilder.append(executeParam.getPageSize());
					excludeColumns.forEach(slatBuilder::append);
					cacheKey = MD5Utils.getMD5(slatBuilder.toString() + querySqlList.get(querySqlList.size() - 1), true,
							32);

					if (!executeParam.getFlush()) {
						try {
							Object object = redisUtils.get(cacheKey);
							if (null != object && executeParam.getCache()) {
								paginate = (PaginateWithQueryColumns) object;
								return paginate;
							}
						} catch (Exception e) {
							log.warn(e.getMessage(), e);
						}
					}
				}

				for (String sql : querySqlList) {
					// 最后执行的是数据查询SQL
					paginate = sqlUtils.syncQuery4Paginate(SqlParseUtils.rebuildSqlWithFragment(sql),
							executeParam.getPageNo(), executeParam.getPageSize(), executeParam.getTotalCount(),
							executeParam.getLimit(), excludeColumns);
				}
			}

		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new ServerException(e.getMessage());
		}

		if (withCache && null != paginate && !CollectionUtils.isEmpty(paginate.getResultList())) {
			redisUtils.set(cacheKey, paginate, executeParam.getExpired(), TimeUnit.SECONDS);
		}

		return paginate;
    }


    @Override
    public List<Map<String, Object>> getDistinctValue(Long id, DistinctParam param, User user) throws NotFoundException, ServerException, UnAuthorizedExecption {
        ViewWithSource viewWithSource = getViewWithSource(id);
        ProjectDetail projectDetail = projectService.getProjectDetail(viewWithSource.getProjectId(), user, false);
        if (!projectService.allowGetData(projectDetail, user)) {
            throw new UnAuthorizedExecption();
        }
        return getDistinctValueData(projectService.isMaintainer(projectDetail, user), viewWithSource, param, user);
    }


    @Override
    public List<Map<String, Object>> getDistinctValueData(boolean isMaintainer, ViewWithSource viewWithSource, DistinctParam param, User user) throws ServerException {

        try {

        	if(StringUtils.isEmpty(viewWithSource.getSql())) {
                return null;
            }
            
            List<SqlVariable> variables = SqlParseUtils.getVariables(viewWithSource.getVariable(), viewWithSource.getSql());
            SqlEntity sqlEntity = sqlParseUtils.parseSql(viewWithSource.getSql(), variables, sqlTempDelimiter, user, isMaintainer);
            packageParams(isMaintainer, viewWithSource.getId(), sqlEntity, variables, param.getParams(), null, user);

            String srcSql = sqlParseUtils.replaceParams(sqlEntity.getSql(), sqlEntity.getQuaryParams(), sqlEntity.getAuthParams(), sqlTempDelimiter);

            Source source = viewWithSource.getSource();

            SqlUtils sqlUtils = this.sqlUtils.init(source);

            List<String> executeSqlList = sqlParseUtils.getSqls(srcSql, false);
            if (!CollectionUtils.isEmpty(executeSqlList)) {
                executeSqlList.forEach(sql -> sqlUtils.execute(sql));
            }

            List<String> querySqlList = sqlParseUtils.getSqls(srcSql, true);
            if (!CollectionUtils.isEmpty(querySqlList)) {
                String cacheKey = null;
                if (null != param) {
                    STGroup stg = new STGroupFile(Constants.SQL_TEMPLATE);
                    ST st = stg.getInstanceOf("queryDistinctSql");
                    st.add("columns", param.getColumns());
                    st.add("filters", convertFilters(param.getFilters(), source));
                    st.add("sql", querySqlList.get(querySqlList.size() - 1));
                    
            		String config = source.getConfig();
            		String url = SourceUtils.getJdbcUrl(config);
            		String version = SourceUtils.getDbVersion(config);
                    st.add("keywordPrefix", SqlUtils.getKeywordPrefix(url, version));
                    st.add("keywordSuffix", SqlUtils.getKeywordSuffix(url, version));

                    String sql = st.render();
                    querySqlList.set(querySqlList.size() - 1, sql);

                    if (null != param.getCache() && param.getCache() && param.getExpired().longValue() > 0L) {
                        cacheKey = MD5Utils.getMD5("DISTINCI" + sql, true, 32);

                        try {
                            Object object = redisUtils.get(cacheKey);
                            if (null != object) {
                                return (List) object;
                            }
                        } catch (Exception e) {
                            log.warn(e.getMessage(), e);
                        }
                    }
                }
                List<Map<String, Object>> list = null;
                for (String sql : querySqlList) {
                    list = sqlUtils.query4List(SqlParseUtils.rebuildSqlWithFragment(sql), -1);
                }

                if (null != param.getCache() && param.getCache() && param.getExpired().longValue() > 0L) {
                    redisUtils.set(cacheKey, list, param.getExpired(), TimeUnit.SECONDS);
                }

                if (null != list) {
                    return list;
                }
            }

        } catch (Exception e) {
        	log.error(e.getMessage(), e);
        	throw new ServerException(e.getMessage());
        }

        return null;
    }


    private Set<String> getExcludeColumnsViaOneView(List<RelRoleView> roleViewList) {
        if (!CollectionUtils.isEmpty(roleViewList)) {
            Set<String> columns = new HashSet<>();
            boolean isFullAuth = false;
            for (RelRoleView r : roleViewList) {
                if (!StringUtils.isEmpty(r.getColumnAuth())) {
                    columns.addAll(JSONUtils.toObjectArray(r.getColumnAuth(), String.class));
                } else {
                    isFullAuth = true;
                    break;
                }
            }
            return isFullAuth ? null : columns;
        }
        return null;
    }


    private List<SqlVariable> getQueryVariables(List<SqlVariable> variables) {
        if (!CollectionUtils.isEmpty(variables)) {
            return variables.stream().filter(v -> QUERYVAR == SqlVariableTypeEnum.typeOf(v.getType())).collect(Collectors.toList());
        }
        return null;
    }

    private List<SqlVariable> getAuthVariables(List<RelRoleView> roleViewList, List<SqlVariable> variables) {

    	if (CollectionUtils.isEmpty(variables)) {
        	return null;
        }

        List<SqlVariable> list = new ArrayList<>();
        variables.forEach(v -> {
            if (null != v.getChannel()) {
                list.add(v);
            }
        });

        if (CollectionUtils.isEmpty(roleViewList)) {
            return list;
        }
        
		Map<String, SqlVariable> map = new HashMap<>();
		List<SqlVariable> authVarables = variables.stream()
				.filter(v -> AUTHVARE == SqlVariableTypeEnum.typeOf(v.getType())).collect(Collectors.toList());
		authVarables.forEach(v -> map.put(v.getName(), v));
		List<SqlVariable> dacVars = authVarables.stream()
				.filter(v -> null != v.getChannel() && !v.getChannel().getBizId().equals(0L))
				.collect(Collectors.toList());

		roleViewList.forEach(r -> {
			if (!StringUtils.isEmpty(r.getRowAuth())) {
				List<AuthParamValue> authParamValues = JSONUtils.toObjectArray(r.getRowAuth(), AuthParamValue.class);
				authParamValues.forEach(v -> {
					if (map.containsKey(v.getName())) {
						SqlVariable sqlVariable = map.get(v.getName());
						if (v.isEnable()) {
							if (CollectionUtils.isEmpty(v.getValues())) {
								List values = new ArrayList<>();
								values.add(NO_AUTH_PERMISSION);
								sqlVariable.setDefaultValues(values);
							} else {
								List<Object> values = sqlVariable.getDefaultValues() == null ? new ArrayList<>()
										: sqlVariable.getDefaultValues();
								values.addAll(v.getValues());
								sqlVariable.setDefaultValues(values);
							}
						} else {
							sqlVariable.setDefaultValues(new ArrayList<>());
						}
						list.add(sqlVariable);
					}
				});
			} else {
				dacVars.forEach(v -> list.add(v));
			}
		});
        
        return list;
    }


    private void packageParams(boolean isProjectMaintainer, Long viewId, SqlEntity sqlEntity, List<SqlVariable> variables, List<Param> paramList, Set<String> excludeColumns, User user) {

        List<SqlVariable> queryVariables = getQueryVariables(variables);
        List<SqlVariable> authVariables = null;
        if (!isProjectMaintainer) {
            List<RelRoleView> roleViewList = relRoleViewExtendMapper.getByUserAndView(user.getId(), viewId);
            authVariables = getAuthVariables(roleViewList, variables);
            if (null != excludeColumns) {
                Set<String> eclmns = getExcludeColumnsViaOneView(roleViewList);
                if (!CollectionUtils.isEmpty(eclmns)) {
                    excludeColumns.addAll(eclmns);
                }
            }
        }

        //查询参数
        if (!CollectionUtils.isEmpty(queryVariables) && !CollectionUtils.isEmpty(sqlEntity.getQuaryParams())) {
            if (!CollectionUtils.isEmpty(paramList)) {
                Map<String, List<SqlVariable>> map = queryVariables.stream().collect(Collectors.groupingBy(SqlVariable::getName));
                paramList.forEach(p -> {
                    if (map.containsKey(p.getName())) {
                        List<SqlVariable> list = map.get(p.getName());
                        if (!CollectionUtils.isEmpty(list)) {
                            SqlVariable v = list.get(list.size() - 1);
                            if (null == sqlEntity.getQuaryParams()) {
                                sqlEntity.setQuaryParams(new HashMap<>());
                            }
                            sqlEntity.getQuaryParams().put(p.getName().trim(), SqlVariableValueTypeEnum.getValue(v.getValueType(), p.getValue(), v.isUdf()));
                        }
                    }
                });
            }

            sqlEntity.getQuaryParams().forEach((k, v) -> {
                if (v instanceof List && ((List) v).size() > 0) {
                    v = ((List) v).stream().collect(Collectors.joining(COMMA)).toString();
                }
                sqlEntity.getQuaryParams().put(k, v);
            });
        }

        //如果当前用户是project的维护者，直接不走行权限
        if (isProjectMaintainer) {
            sqlEntity.setAuthParams(null);
            return;
        }

        //权限参数
        if (!CollectionUtils.isEmpty(authVariables)) {
        	ExecutorService executorService = Executors.newFixedThreadPool(authVariables.size() > 8 ? 8 : authVariables.size());
            Map<String, Set<String>> map = new Hashtable<>();
            List<Future> futures = new ArrayList<>(authVariables.size());
            try {
                authVariables.forEach(sqlVariable -> {
					futures.add(executorService.submit(() -> {
						if (null != sqlVariable) {
							Set<String> vSet = null;
							if (map.containsKey(sqlVariable.getName().trim())) {
								vSet = map.get(sqlVariable.getName().trim());
							} else {
								vSet = new HashSet<>();
							}

							List<String> values = sqlParseUtils.getAuthVarValue(sqlVariable, user.getEmail());
							if (null == values) {
								vSet.add(NO_AUTH_PERMISSION);
							} else if (!values.isEmpty()) {
								vSet.addAll(values);
							}
							map.put(sqlVariable.getName().trim(), vSet);
						}
					}));
                });

                for (Future future : futures) {
					try {
						future.get();
					} catch (ExecutionException e) {
						executorService.shutdownNow();
						throw new ServerException(e.getMessage());
					}
				}
            } catch (Exception e) {
				log.error(e.getMessage(), e);
            } finally {
                executorService.shutdown();
            }

            if (!CollectionUtils.isEmpty(map)) {
                if (null == sqlEntity.getAuthParams()) {
                    sqlEntity.setAuthParams(new HashMap<>());
                }
                map.forEach((k, v) -> sqlEntity.getAuthParams().put(k, new ArrayList<String>(v)));
            }
        } else {
            sqlEntity.setAuthParams(null);
        }
    }


	private void checkAndInsertRoleParam(String sqlVarible, List<RelRoleViewDTO> roles, User user, View view) {

		List<SqlVariable> variables = JSONUtils.toObjectArray(sqlVarible, SqlVariable.class);
        if (CollectionUtils.isEmpty(roles)) {
            relRoleViewExtendMapper.deleteByViewId(view.getId());
            return;
        }
        
        roleParamThreadPool.submit(() -> {
        	Set<String> vars = null, columns = null;

            if (!CollectionUtils.isEmpty(variables)) {
                vars = variables.stream().map(SqlVariable::getName).collect(Collectors.toSet());
            }
            if (!StringUtils.isEmpty(view.getModel())) {
                columns = JSONUtils.toObject(view.getModel(), Map.class).keySet();
            }

            Set<String> finalColumns = columns;
            Set<String> finalVars = vars;

            List<RelRoleView> relRoleViews = new ArrayList<>();
            roles.forEach(r -> {
                if (r.getRoleId().longValue() <= 0L) {
                   return;
                }
                
                String rowAuth = null, columnAuth = null;
                if (!StringUtils.isEmpty(r.getRowAuth())) {
                	List<Map> rowAuthList = JSONUtils.toObjectArray(r.getRowAuth(), Map.class);
                	if (!CollectionUtils.isEmpty(rowAuthList)) {
                		List<Map> newRowAuthList = new ArrayList<Map>();
                		for (Map jsonMap : rowAuthList) {
                			String name = (String)jsonMap.get(SQL_VARABLE_KEY);
                			if (finalVars.contains(name)) {
                				newRowAuthList.add(jsonMap);
                            }
						}
                		rowAuth = JSONUtils.toString(newRowAuthList);
                		newRowAuthList.clear();
                    }
                }

                if (null != finalColumns && !StringUtils.isEmpty(r.getColumnAuth())) {
                    List<String> clms = JSONUtils.toObjectArray(r.getColumnAuth(), String.class);
                    List<String> collect = clms.stream().filter(c -> finalColumns.contains(c)).collect(Collectors.toList());
                    columnAuth = JSONUtils.toString(collect);
                }

                RelRoleView relRoleView = new RelRoleView();
                relRoleView.setRoleId(r.getRoleId());
                relRoleView.setViewId(view.getId());
                relRoleView.setRowAuth(rowAuth);
                relRoleView.setColumnAuth(columnAuth);
                relRoleView.setCreateBy(user.getId());
                relRoleView.setCreateTime(new Date());
                relRoleViews.add(relRoleView);
            });

            if (!CollectionUtils.isEmpty(relRoleViews)) {
                relRoleViewExtendMapper.insertBatch(relRoleViews);
            }
        });
    }

}

