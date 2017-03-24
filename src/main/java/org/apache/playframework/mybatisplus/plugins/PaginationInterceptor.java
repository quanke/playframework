/**
 * Copyright (c) 2011-2020, hubin (jobob@qq.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.playframework.mybatisplus.plugins;

import com.baomidou.mybatisplus.MybatisDefaultParameterHandler;
import com.baomidou.mybatisplus.entity.CountOptimize;
import com.baomidou.mybatisplus.exceptions.MybatisPlusException;
import com.baomidou.mybatisplus.plugins.pagination.DialectFactory;
import com.baomidou.mybatisplus.plugins.pagination.IDialect;
import com.baomidou.mybatisplus.plugins.pagination.Pagination;
import com.baomidou.mybatisplus.toolkit.IOUtils;
import com.baomidou.mybatisplus.toolkit.SqlUtils;
import com.baomidou.mybatisplus.toolkit.StringUtils;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.playframework.util.NetUtils;
import org.apache.playframework.util.Request;
import org.apache.playframework.util.SPUtil;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * <p>
 * 分页拦截器
 * </p>
 *
 * @author hubin
 * @Date 2016-01-23
 */
@Intercepts({
		@Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class, RowBounds.class,
				ResultHandler.class }),
		@Signature(type = StatementHandler.class, method = "prepare", args = { Connection.class, Integer.class }) })
public class PaginationInterceptor implements Interceptor {

	/* 溢出总页数，设置第一页 */
	private boolean overflowCurrent = false;
	/* Count优化方式 */
	private String optimizeType = "default";
	/* 方言类型 */
	private String dialectType;
	/* 方言实现类 */
	private String dialectClazz;

	public Object intercept(Invocation invocation) throws Throwable {

		Object target = invocation.getTarget();
		if (target instanceof StatementHandler) {
			StatementHandler statementHandler = (StatementHandler) target;
			MetaObject metaStatementHandler = SystemMetaObject.forObject(statementHandler);
			RowBounds rowBounds = (RowBounds) metaStatementHandler.getValue("delegate.rowBounds");
			Configuration configuration = (Configuration) metaStatementHandler.getValue("delegate.configuration");
			BoundSql boundSql = (BoundSql) metaStatementHandler.getValue("delegate.boundSql");
			String originalSql = (String) boundSql.getSql();
			/* 需要分页的场合 */
			if (rowBounds != null && rowBounds != RowBounds.DEFAULT) {
				
				/* 定义数据库方言 */
				IDialect dialect = getiDialect();

				/*
				 * <p> 禁用内存分页 </p> <p> 内存分页会查询所有结果出来处理（这个很吓人的），如果结果变化频繁这个数据还会不准。
				 * </p>
				 */
				metaStatementHandler.setValue("delegate.rowBounds.offset", RowBounds.NO_ROW_OFFSET);
				metaStatementHandler.setValue("delegate.rowBounds.limit", RowBounds.NO_ROW_LIMIT);

				/**
				 * <p>
				 * 分页逻辑
				 * </p>
				 * <p>
				 * 查询总记录数 count
				 * </p>
				 */
				if (rowBounds instanceof Pagination) {
					Pagination page = (Pagination) rowBounds;
					boolean orderBy = true;
					if (page.isSearchCount()) {
						/*
						 * COUNT 查询，去掉 ORDER BY 优化执行 SQL
						 */
						CountOptimize countOptimize = SqlUtils.getCountOptimize(originalSql, optimizeType, dialectType,
								page.isOptimizeCount());
						orderBy = countOptimize.isOrderBy();
					}
					/* 执行 SQL */
					String buildSql = SqlUtils.concatOrderBy(originalSql, page, orderBy);
					originalSql = dialect.buildPaginationSql(buildSql, page.getOffsetCurrent(), page.getSize());
				}
			}
			/**
			 * 查询 SQL 设置
			 */
			metaStatementHandler.setValue("delegate.boundSql.sql", buildSql(originalSql, configuration));
		} else {
			MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
			Object parameterObject = null;
			RowBounds rowBounds = null;
			if (invocation.getArgs().length > 1) {
				parameterObject = invocation.getArgs()[1];
				rowBounds = (RowBounds) invocation.getArgs()[2];
			}
			/* 不需要分页的场合 */
			if (rowBounds == null || rowBounds == RowBounds.DEFAULT) {
				return invocation.proceed();
			}

			BoundSql boundSql = mappedStatement.getBoundSql(parameterObject);
			/*
			 * <p> 禁用内存分页 </p> <p> 内存分页会查询所有结果出来处理（这个很吓人的），如果结果变化频繁这个数据还会不准。
			 * </p>
			 */
			String originalSql = (String) boundSql.getSql();

			/**
			 * <p>
			 * 分页逻辑
			 * </p>
			 * <p>
			 * 查询总记录数 count
			 * </p>
			 */
			if (rowBounds instanceof Pagination) {
				Connection connection = null;
				try {
					connection = mappedStatement.getConfiguration().getEnvironment().getDataSource().getConnection();
					Pagination page = (Pagination) rowBounds;
					if (page.isSearchCount()) {
						/*
						 * COUNT 查询，去掉 ORDER BY 优化执行 SQL
						 */
						CountOptimize countOptimize = SqlUtils.getCountOptimize(originalSql, optimizeType, dialectType,
								page.isOptimizeCount());
						page = this.count(countOptimize.getCountSQL(), connection, mappedStatement, boundSql, page);
						/** 总数 0 跳出执行 */
						if (page.getTotal() <= 0) {
							return invocation.proceed();
						}
					}
				} finally {
					IOUtils.closeQuietly(connection);
				}
			}
		}

		return invocation.proceed();

	}

	/**
	 * 获取数据库方言
	 *
	 * @return
	 * @throws Exception
	 */
	private IDialect getiDialect() throws Exception {
		IDialect dialect = null;
		if (StringUtils.isNotEmpty(dialectType)) {
		 
		} else {
			if (StringUtils.isNotEmpty(dialectClazz)) {
				try {
					Class<?> clazz = Class.forName(dialectClazz);
					if (IDialect.class.isAssignableFrom(clazz)) {
						dialect = (IDialect) clazz.newInstance();
					}
				} catch (ClassNotFoundException e) {
					throw new MybatisPlusException("Class :" + dialectClazz + " is not found");
				}
			}
		}
		/* 未配置方言则抛出异常 */
		if (dialect == null) {
			throw new MybatisPlusException("The value of the dialect property in mybatis configuration.xml is not defined.");
		}
		return dialect;
	}

	/**
	 * 查询总记录条数
	 *
	 * @param sql
	 * @param connection
	 * @param mappedStatement
	 * @param boundSql
	 * @param page
	 */
	public Pagination count(String sql, Connection connection, MappedStatement mappedStatement, BoundSql boundSql, Pagination page) {
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = connection.prepareStatement(sql);
			DefaultParameterHandler parameterHandler = new MybatisDefaultParameterHandler(mappedStatement,
					boundSql.getParameterObject(), boundSql);
			parameterHandler.setParameters(pstmt);
			rs = pstmt.executeQuery();
			int total = 0;
			if (rs.next()) {
				total = rs.getInt(1);
			}
			page.setTotal(total);
			/*
			 * 溢出总页数，设置第一页
			 */
			if (overflowCurrent && (page.getCurrent() > page.getPages())) {
				page = new Pagination(1, page.getSize());
				page.setTotal(total);
			}
		} catch (Exception e) {
			// ignored
		} finally {
			 
		}
		return page;
	}

	public Object plugin(Object target) {
		if (target instanceof Executor) {
			return Plugin.wrap(target, this);
		}
		if (target instanceof StatementHandler) {
			return Plugin.wrap(target, this);
		}
		return target;
	}

	public void setProperties(Properties prop) {
		String dialectType = prop.getProperty("dialectType");
		String dialectClazz = prop.getProperty("dialectClazz");
		if (StringUtils.isNotEmpty(dialectType)) {
			this.dialectType = dialectType;
		}
		if (StringUtils.isNotEmpty(dialectClazz)) {
			this.dialectClazz = dialectClazz;
		}
	}

	public void setDialectType(String dialectType) {
		this.dialectType = dialectType;
	}

	public void setDialectClazz(String dialectClazz) {
		this.dialectClazz = dialectClazz;
	}

	public void setOverflowCurrent(boolean overflowCurrent) {
		this.overflowCurrent = overflowCurrent;
	}

	public void setOptimizeType(String optimizeType) {
		this.optimizeType = optimizeType;
	}
	
	
	private static String ip;
	private static String pid = "unknow";

	static {
		pid = ManagementFactory.getRuntimeMXBean().getName();
		ip = NetUtils.getLocalAddress().getHostAddress();
		pid=pid.replaceAll("localhost", ip);
	}
	
	private String buildSql(String sql, Configuration configuration) {

		if (sql.indexOf(" /*from_api:") != -1) {
			return sql;
		} else {
			String db = null;
			Environment env = null;
			if (configuration != null) {
				env = configuration.getEnvironment();
				/*db = configuration.getVariables().getProperty("database_name");*/
			}
			
			/**
			 * 删除sql \/*...*\/的多行注释，和以 -- 开始的单行注释
			 */
			Pattern p = Pattern
					.compile("(?ms)('(?:''|[^'])*')|--.*?$|/\\*.*?\\*/");
			sql = p.matcher(sql).replaceAll("$1");//\/*...*\/的多行注释，和以 -- 开始的单行注释 防止sql注入
			StringBuilder sb = new StringBuilder(sql);
			sb.append(" /*from_api:");
			sb.append(Request.getId());
			sb.append(pid);
			sb.append(" spid=");
			sb.append(SPUtil.getSpid());
			sb.append(" dbname=");
			sb.append(db);
			sb.append("*/");
			sql = sb.toString().replaceAll("\n", " ").replaceAll("\t", " ")
					.replaceAll("[\\s]+", " ");//格式化sql语句
		 
			return sql;
		}
	}
	
}
