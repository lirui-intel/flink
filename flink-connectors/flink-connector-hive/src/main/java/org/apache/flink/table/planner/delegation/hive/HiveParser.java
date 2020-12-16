/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.delegation.hive;

import org.apache.flink.connectors.hive.FlinkHiveException;
import org.apache.flink.table.api.SqlParserException;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.catalog.CatalogManager;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.CatalogTableImpl;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.UnresolvedIdentifier;
import org.apache.flink.table.catalog.config.CatalogConfig;
import org.apache.flink.table.catalog.hive.HiveCatalog;
import org.apache.flink.table.catalog.hive.client.HiveShim;
import org.apache.flink.table.catalog.hive.client.HiveShimLoader;
import org.apache.flink.table.catalog.hive.util.HiveTableUtil;
import org.apache.flink.table.module.hive.udf.generic.HiveGenericUDFGrouping;
import org.apache.flink.table.operations.CatalogSinkModifyOperation;
import org.apache.flink.table.operations.ExplainOperation;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.ddl.CreateTableOperation;
import org.apache.flink.table.planner.calcite.CalciteParser;
import org.apache.flink.table.planner.calcite.FlinkPlannerImpl;
import org.apache.flink.table.planner.calcite.SqlExprToRexConverter;
import org.apache.flink.table.planner.delegation.ParserImpl;
import org.apache.flink.table.planner.delegation.PlannerContext;
import org.apache.flink.table.planner.operations.PlannerQueryOperation;
import org.apache.flink.table.planner.plan.FlinkCalciteCatalogReader;
import org.apache.flink.table.planner.plan.nodes.hive.HiveDistribution;
import org.apache.flink.util.Preconditions;

import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationImpl;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.HiveParserContext;
import org.apache.hadoop.hive.ql.HiveParserQueryState;
import org.apache.hadoop.hive.ql.exec.DDLTask;
import org.apache.hadoop.hive.ql.exec.FunctionInfo;
import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.lockmgr.LockException;
import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.optimizer.calcite.CalciteSemanticException;
import org.apache.hadoop.hive.ql.optimizer.calcite.translator.HiveParserSqlFunctionConverter;
import org.apache.hadoop.hive.ql.optimizer.calcite.translator.HiveParserTypeConverter;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.BaseSemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.HiveASTParseUtils;
import org.apache.hadoop.hive.ql.parse.HiveASTParser;
import org.apache.hadoop.hive.ql.parse.HiveParserCalcitePlanner;
import org.apache.hadoop.hive.ql.parse.HiveParserQB;
import org.apache.hadoop.hive.ql.parse.ParseException;
import org.apache.hadoop.hive.ql.parse.QBMetaData;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.CreateTableDesc;
import org.apache.hadoop.hive.ql.plan.DDLWork;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.udf.SettableUDF;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A Parser that uses Hive's planner to parse a statement.
 */
public class HiveParser extends ParserImpl {

	private static final Logger LOG = LoggerFactory.getLogger(HiveParser.class);

	// need to maintain the ASTNode types for DDLs
	private static final Set<Integer> DDL_NODES;

	static {
		DDL_NODES = new HashSet<>(Arrays.asList(HiveASTParser.TOK_ALTERTABLE, HiveASTParser.TOK_ALTERVIEW,
				HiveASTParser.TOK_CREATEDATABASE, HiveASTParser.TOK_DROPDATABASE, HiveASTParser.TOK_SWITCHDATABASE,
				HiveASTParser.TOK_DROPTABLE, HiveASTParser.TOK_DROPVIEW, HiveASTParser.TOK_DROP_MATERIALIZED_VIEW,
				HiveASTParser.TOK_DESCDATABASE, HiveASTParser.TOK_DESCTABLE, HiveASTParser.TOK_DESCFUNCTION,
				HiveASTParser.TOK_MSCK, HiveASTParser.TOK_ALTERINDEX_REBUILD, HiveASTParser.TOK_ALTERINDEX_PROPERTIES,
				HiveASTParser.TOK_SHOWDATABASES, HiveASTParser.TOK_SHOWTABLES, HiveASTParser.TOK_SHOWCOLUMNS,
				HiveASTParser.TOK_SHOW_TABLESTATUS, HiveASTParser.TOK_SHOW_TBLPROPERTIES, HiveASTParser.TOK_SHOW_CREATEDATABASE,
				HiveASTParser.TOK_SHOW_CREATETABLE, HiveASTParser.TOK_SHOWFUNCTIONS, HiveASTParser.TOK_SHOWPARTITIONS,
				HiveASTParser.TOK_SHOWINDEXES, HiveASTParser.TOK_SHOWLOCKS, HiveASTParser.TOK_SHOWDBLOCKS,
				HiveASTParser.TOK_SHOW_COMPACTIONS, HiveASTParser.TOK_SHOW_TRANSACTIONS, HiveASTParser.TOK_ABORT_TRANSACTIONS,
				HiveASTParser.TOK_SHOWCONF, HiveASTParser.TOK_SHOWVIEWS, HiveASTParser.TOK_CREATEINDEX, HiveASTParser.TOK_DROPINDEX,
				HiveASTParser.TOK_ALTERTABLE_CLUSTER_SORT, HiveASTParser.TOK_LOCKTABLE, HiveASTParser.TOK_UNLOCKTABLE,
				HiveASTParser.TOK_LOCKDB, HiveASTParser.TOK_UNLOCKDB, HiveASTParser.TOK_CREATEROLE, HiveASTParser.TOK_DROPROLE,
				HiveASTParser.TOK_GRANT, HiveASTParser.TOK_REVOKE, HiveASTParser.TOK_SHOW_GRANT, HiveASTParser.TOK_GRANT_ROLE,
				HiveASTParser.TOK_REVOKE_ROLE, HiveASTParser.TOK_SHOW_ROLE_GRANT, HiveASTParser.TOK_SHOW_ROLE_PRINCIPALS,
				HiveASTParser.TOK_SHOW_ROLE_PRINCIPALS, HiveASTParser.TOK_ALTERDATABASE_PROPERTIES, HiveASTParser.TOK_ALTERDATABASE_OWNER,
				HiveASTParser.TOK_TRUNCATETABLE, HiveASTParser.TOK_SHOW_SET_ROLE, HiveASTParser.TOK_CACHE_METADATA,
				HiveASTParser.TOK_CREATEMACRO, HiveASTParser.TOK_DROPMACRO, HiveASTParser.TOK_CREATETABLE,
				HiveASTParser.TOK_CREATEFUNCTION, HiveASTParser.TOK_DROPFUNCTION, HiveASTParser.TOK_RELOADFUNCTION));
	}

	private final PlannerContext plannerContext;
	private final FlinkCalciteCatalogReader catalogReader;
	private final FrameworkConfig frameworkConfig;

	HiveParser(
			CatalogManager catalogManager,
			Supplier<FlinkPlannerImpl> validatorSupplier,
			Supplier<CalciteParser> calciteParserSupplier,
			Function<TableSchema, SqlExprToRexConverter> sqlExprToRexConverterCreator,
			PlannerContext plannerContext) {
		super(catalogManager, validatorSupplier, calciteParserSupplier, sqlExprToRexConverterCreator);
		this.plannerContext = plannerContext;
		this.catalogReader = plannerContext.createCatalogReader(
				false, catalogManager.getCurrentCatalog(), catalogManager.getCurrentDatabase());
		this.frameworkConfig = plannerContext.createFrameworkConfig();
	}

	@Override
	public List<Operation> parse(String statements) {
		CatalogManager catalogManager = getCatalogManager();
		Catalog currentCatalog = catalogManager.getCatalog(catalogManager.getCurrentCatalog()).orElse(null);
		if (!(currentCatalog instanceof HiveCatalog)) {
			LOG.warn("Current catalog is not HiveCatalog. Falling back to Flink's planner.");
			return super.parse(statements);
		}
		HiveConf hiveConf = new HiveConf(((HiveCatalog) currentCatalog).getHiveConf());
		hiveConf.setVar(HiveConf.ConfVars.DYNAMICPARTITIONINGMODE, "nonstrict");
		hiveConf.set("hive.allow.udf.load.on.demand", "false");
		HiveShim hiveShim = HiveShimLoader.loadHiveShim(((HiveCatalog) currentCatalog).getHiveVersion());
		try {
			// creates SessionState
			SessionState sessionState = new SessionState(hiveConf);
			sessionState.initTxnMgr(hiveConf);
			sessionState.setCurrentDatabase(catalogManager.getCurrentDatabase());
			// some Hive functions needs the timestamp
			sessionState.setupQueryCurrentTimestamp();
			SessionState.start(sessionState);
			// We override Hive's grouping function. Refer to the implementation for more details.
			FunctionRegistry.registerTemporaryUDF("grouping", HiveGenericUDFGrouping.class);
			List<Operation> operations = new ArrayList<>();
			for (String cmd : HiveParserUtils.splitSQLStatements(statements)) {
				operations.addAll(processCmd(cmd, hiveConf, hiveShim));
			}
			return operations;
		} catch (LockException e) {
			throw new FlinkHiveException("Failed to init SessionState", e);
		} finally {
			clearSessionState(hiveConf);
		}
	}

	private List<Operation> processCmd(String cmd, HiveConf hiveConf, HiveShim hiveShim) {
		try {
			final HiveParserContext context = new HiveParserContext(hiveConf);
			// parse statement to get AST
			final ASTNode node = HiveASTParseUtils.parse(cmd, context);
			// generate Calcite plan
			Operation res;
			if (DDL_NODES.contains(node.getType())) {
				return super.parse(cmd);
//				res = handleDDL(node, hiveAnalyzer, context);
			} else if (node.getType() == HiveASTParser.TOK_EXPLAIN) {
				// first child is the underlying explicandum
				ASTNode input = (ASTNode) node.getChild(0);
				res = new ExplainOperation(analyzeQuery(context, hiveConf, hiveShim, cmd, input));
			} else {
				res = analyzeQuery(context, hiveConf, hiveShim, cmd, node);
			}
			return Collections.singletonList(res);
		} catch (ParseException e) {
			// ParseException can happen for flink-specific statements, e.g. catalog DDLs
			try {
				return super.parse(cmd);
			} catch (SqlParserException parserException) {
				throw new SqlParserException("SQL parse failed", e);
			}
		} catch (SemanticException | IOException e) {
			// disable fallback for now
			throw new FlinkHiveException("HiveParser failed to parse " + cmd, e);
		}
	}

	private Operation analyzeQuery(HiveParserContext context, HiveConf hiveConf, HiveShim hiveShim, String cmd, ASTNode node)
			throws SemanticException {
		HiveParserCalcitePlanner analyzer = new HiveParserCalcitePlanner(
				new HiveParserQueryState(hiveConf),
				plannerContext,
				catalogReader,
				frameworkConfig,
				getCatalogManager(),
				hiveShim);
		analyzer.initCtx(context);
		analyzer.init(false);
		RelNode relNode = analyzer.genLogicalPlan(node);
		Preconditions.checkState(relNode != null,
				String.format("%s failed to generate plan for %s", analyzer.getClass().getSimpleName(), cmd));

		// if not a query, treat it as an insert
		if (!analyzer.getQB().getIsQuery()) {
			return createInsertOperation(analyzer, relNode);
		} else {
			return new PlannerQueryOperation(relNode);
		}
	}

	private Operation handleDDL(ASTNode node, BaseSemanticAnalyzer hiveAnalyzer, Context context) throws SemanticException {
		hiveAnalyzer.analyze(node, context);
		DDLTask ddlTask = retrieveDDLTask(hiveAnalyzer);
		// this can happen, e.g. table already exists and "if not exists" is specified
		if (ddlTask == null) {
			return null;
		}
		DDLWork ddlWork = ddlTask.getWork();
		if (ddlWork.getCreateTblDesc() != null) {
			return handleCreateTable(ddlWork.getCreateTblDesc());
		}
		throw new UnsupportedOperationException("Unsupported DDL");
	}

	private Operation handleCreateTable(CreateTableDesc createTableDesc) throws SemanticException {
		// TODO: support NOT NULL and PK
		TableSchema tableSchema = HiveTableUtil.createTableSchema(
				createTableDesc.getCols(), createTableDesc.getPartCols(), Collections.emptySet(), null);
		List<String> partitionCols = HiveCatalog.getFieldNames(createTableDesc.getPartCols());
		// TODO: add more properties like SerDe, location, etc
		Map<String, String> tableProps = new HashMap<>(createTableDesc.getTblProps());
		tableProps.putAll(createTableDesc.getSerdeProps());
		tableProps.put(CatalogConfig.IS_GENERIC, "false");
		CatalogTable catalogTable = new CatalogTableImpl(tableSchema, partitionCols, tableProps, createTableDesc.getComment());
		String dbName = createTableDesc.getDatabaseName();
		String tabName = createTableDesc.getTableName();
		if (dbName == null || tabName.contains(".")) {
			String[] names = Utilities.getDbTableName(tabName);
			dbName = names[0];
			tabName = names[1];
		}
		UnresolvedIdentifier unresolvedIdentifier = UnresolvedIdentifier.of(dbName, tabName);
		ObjectIdentifier identifier = getCatalogManager().qualifyIdentifier(unresolvedIdentifier);
		return new CreateTableOperation(identifier, catalogTable, false, false);
	}

	private DDLTask retrieveDDLTask(BaseSemanticAnalyzer hiveAnalyzer) {
		Set<DDLTask> set = new HashSet<>();
		Deque<Task<?>> queue = new ArrayDeque<>(hiveAnalyzer.getRootTasks());
		while (!queue.isEmpty()) {
			Task<?> hiveTask = queue.remove();
			if (hiveTask instanceof DDLTask) {
				set.add((DDLTask) hiveTask);
			}
			if (hiveTask.getChildTasks() != null) {
				queue.addAll(hiveTask.getChildTasks());
			}
		}
		Preconditions.checkState(set.size() <= 1, "Expect at most 1 DDLTask, actually get " + set.size());
		return set.isEmpty() ? null : set.iterator().next();
	}

	private void clearSessionState(HiveConf hiveConf) {
		SessionState sessionState = SessionState.get();
		if (sessionState != null) {
			try {
				sessionState.close();
				List<Path> toDelete = new ArrayList<>();
				toDelete.add(SessionState.getHDFSSessionPath(hiveConf));
				toDelete.add(SessionState.getLocalSessionPath(hiveConf));
				for (Path path : toDelete) {
					FileSystem fs = path.getFileSystem(hiveConf);
					fs.delete(path, true);
				}
			} catch (IOException e) {
				LOG.warn("Error closing SessionState", e);
			}
		}
	}

	private Operation createInsertOperation(HiveParserCalcitePlanner analyzer, RelNode queryRelNode) throws SemanticException {
		// sanity check
		Preconditions.checkArgument(queryRelNode instanceof Project || queryRelNode instanceof Sort || queryRelNode instanceof HiveDistribution,
				"Expect top RelNode to be Project, Sort, or HiveDistribution, actually got " + queryRelNode);
		if (!(queryRelNode instanceof Project)) {
			RelNode parent = ((SingleRel) queryRelNode).getInput();
			// SEL + SORT or SEL + DIST + LIMIT
			Preconditions.checkArgument(parent instanceof Project || parent instanceof HiveDistribution,
					"Expect input to be a Project or HiveDistribution, actually got " + parent);
			if (parent instanceof HiveDistribution) {
				RelNode grandParent = ((HiveDistribution) parent).getInput();
				Preconditions.checkArgument(grandParent instanceof Project,
						"Expect input of HiveDistribution to be a Project, actually got " + grandParent);
			}
		}
		HiveParserQB topQB = analyzer.getQB();
		QBMetaData qbMetaData = topQB.getMetaData();
		// decide the dest table
		Map<String, Table> nameToDestTable = qbMetaData.getNameToDestTable();
		Map<String, Partition> nameToDestPart = qbMetaData.getNameToDestPartition();
		// for now we only support inserting to a single table
		Preconditions.checkState(nameToDestTable.size() <= 1 && nameToDestPart.size() <= 1,
				"Only support inserting to 1 table");
		Table destTable;
		String insClauseName;
		if (!nameToDestTable.isEmpty()) {
			insClauseName = nameToDestTable.keySet().iterator().next();
			destTable = nameToDestTable.values().iterator().next();
		} else if (!nameToDestPart.isEmpty()) {
			insClauseName = nameToDestPart.keySet().iterator().next();
			destTable = nameToDestPart.values().iterator().next().getTable();
		} else {
			// happens for INSERT DIRECTORY
			throw new SemanticException("INSERT DIRECTORY is not supported");
		}

		// handle dest schema, e.g. insert into dest(.,.,.) select ...
		queryRelNode = handleDestSchema((SingleRel) queryRelNode, destTable, analyzer.getDestSchemaForClause(insClauseName));

		// create identifier
		List<String> targetTablePath = Arrays.asList(destTable.getDbName(), destTable.getTableName());
		UnresolvedIdentifier unresolvedIdentifier = UnresolvedIdentifier.of(targetTablePath);
		ObjectIdentifier identifier = getCatalogManager().qualifyIdentifier(unresolvedIdentifier);

		// track each target col and its expected type
		RelDataTypeFactory typeFactory = plannerContext.getTypeFactory();
		LinkedHashMap<String, RelDataType> targetColToCalcType = new LinkedHashMap<>();
		List<TypeInfo> targetHiveTypes = new ArrayList<>();
		List<FieldSchema> allCols = new ArrayList<>(destTable.getCols());
		allCols.addAll(destTable.getPartCols());
		for (FieldSchema col : allCols) {
			TypeInfo hiveType = TypeInfoUtils.getTypeInfoFromTypeString(col.getType());
			targetHiveTypes.add(hiveType);
			targetColToCalcType.put(col.getName(), HiveParserTypeConverter.convert(hiveType, typeFactory));
		}

		// decide static partition specs
		Map<String, String> staticPartSpec = new LinkedHashMap<>();
		if (destTable.isPartitioned()) {
			List<String> partCols = HiveCatalog.getFieldNames(destTable.getTTable().getPartitionKeys());

			if (!nameToDestPart.isEmpty()) {
				// static partition
				Partition destPart = nameToDestPart.values().iterator().next();
				Preconditions.checkState(partCols.size() == destPart.getValues().size(),
						"Part cols and static spec doesn't match");
				for (int i = 0; i < partCols.size(); i++) {
					staticPartSpec.put(partCols.get(i), destPart.getValues().get(i));
				}
			} else {
				// dynamic partition
				Map<String, String> spec = qbMetaData.getPartSpecForAlias(insClauseName);
				if (spec != null) {
					for (String partCol : partCols) {
						String val = spec.get(partCol);
						if (val != null) {
							staticPartSpec.put(partCol, val);
						}
					}
				}
			}
		}

		// add static partitions to query source
		if (!staticPartSpec.isEmpty()) {
			if (queryRelNode instanceof Project) {
				queryRelNode = replaceProjectForStaticPart((Project) queryRelNode, staticPartSpec, destTable, targetColToCalcType);
			} else if (queryRelNode instanceof Sort) {
				Sort sort = (Sort) queryRelNode;
				RelNode oldInput = sort.getInput();
				RelNode newInput;
				if (oldInput instanceof HiveDistribution) {
					newInput = replaceDistForStaticParts((HiveDistribution) oldInput, destTable, staticPartSpec, targetColToCalcType);
				} else {
					newInput = replaceProjectForStaticPart((Project) oldInput, staticPartSpec, destTable, targetColToCalcType);
					// we may need to shift the field collations
					final int numDynmPart = destTable.getTTable().getPartitionKeys().size() - staticPartSpec.size();
					if (!sort.getCollation().getFieldCollations().isEmpty() && numDynmPart > 0) {
						sort.replaceInput(0, null);
						sort = LogicalSort.create(newInput,
								shiftRelCollation(sort.getCollation(), (Project) oldInput, staticPartSpec.size(), numDynmPart),
								sort.offset, sort.fetch);
					}
				}
				sort.replaceInput(0, newInput);
				queryRelNode = sort;
			} else {
				queryRelNode = replaceDistForStaticParts((HiveDistribution) queryRelNode, destTable, staticPartSpec, targetColToCalcType);
			}
		}

		// add type conversions
		queryRelNode = addTypeConversions(queryRelNode, new ArrayList<>(targetColToCalcType.values()), targetHiveTypes);

		// decide whether it's overwrite
		boolean overwrite = topQB.getParseInfo().getInsertOverwriteTables().keySet().stream().map(String::toLowerCase).collect(Collectors.toSet())
				.contains(destTable.getDbName() + "." + destTable.getTableName());

		return new CatalogSinkModifyOperation(identifier, new PlannerQueryOperation(queryRelNode), staticPartSpec, overwrite, Collections.emptyMap());
	}

	private RelNode replaceDistForStaticParts(HiveDistribution hiveDist, Table destTable,
			Map<String, String> staticPartSpec, Map<String, RelDataType> targetColToType) {
		Project project = (Project) hiveDist.getInput();
		RelNode expandedProject = replaceProjectForStaticPart(project, staticPartSpec, destTable, targetColToType);
		hiveDist.replaceInput(0, null);
		final int toShift = staticPartSpec.size();
		final int numDynmPart = destTable.getTTable().getPartitionKeys().size() - toShift;
		return HiveDistribution.create(
				expandedProject,
				shiftRelCollation(hiveDist.getCollation(), project, toShift, numDynmPart),
				shiftDistKeys(hiveDist.getDistKeys(), project, toShift, numDynmPart));
	}

	private static List<Integer> shiftDistKeys(List<Integer> distKeys, Project origProject, int toShift, int numDynmPart) {
		List<Integer> shiftedDistKeys = new ArrayList<>(distKeys.size());
		// starting index of dynamic parts, static parts needs to be inserted before them
		final int insertIndex = origProject.getProjects().size() - numDynmPart;
		for (Integer key : distKeys) {
			if (key >= insertIndex) {
				key += toShift;
			}
			shiftedDistKeys.add(key);
		}
		return shiftedDistKeys;
	}

	private RelCollation shiftRelCollation(RelCollation collation, Project origProject, int toShift, int numDynmPart) {
		List<RelFieldCollation> fieldCollations = collation.getFieldCollations();
		// starting index of dynamic parts, static parts needs to be inserted before them
		final int insertIndex = origProject.getProjects().size() - numDynmPart;
		List<RelFieldCollation> shiftedCollations = new ArrayList<>(fieldCollations.size());
		for (RelFieldCollation fieldCollation : fieldCollations) {
			if (fieldCollation.getFieldIndex() >= insertIndex) {
				fieldCollation = fieldCollation.withFieldIndex(fieldCollation.getFieldIndex() + toShift);
			}
			shiftedCollations.add(fieldCollation);
		}
		return plannerContext.getCluster().traitSet().canonize(RelCollationImpl.of(shiftedCollations));
	}

	private RelNode addTypeConversions(RelNode queryRelNode, List<RelDataType> targetCalcTypes, List<TypeInfo> targetHiveTypes)
			throws SemanticException {
		if (queryRelNode instanceof Project) {
			return replaceProjectForTypeConversion((Project) queryRelNode, targetCalcTypes, targetHiveTypes);
		} else {
			// current node is not Project, we search for it in inputs
			RelNode newInput = addTypeConversions(queryRelNode.getInput(0), targetCalcTypes, targetHiveTypes);
			queryRelNode.replaceInput(0, newInput);
			return queryRelNode;
		}
	}

	private RexNode createConversionCast(RexNode srcRex, PrimitiveTypeInfo targetHiveType, RelDataType targetCalType,
			ConvertSqlFunctionCopier funcConverter) throws SemanticException {
		// hive implements CAST with UDFs
		String udfName = TypeInfoUtils.getBaseName(targetHiveType.getTypeName());
		FunctionInfo functionInfo;
		try {
			functionInfo = FunctionRegistry.getFunctionInfo(udfName);
		} catch (SemanticException e) {
			throw new SemanticException(String.format("Failed to get UDF %s for casting", udfName), e);
		}
		if (functionInfo == null || functionInfo.getGenericUDF() == null) {
			throw new SemanticException(String.format("Failed to get UDF %s for casting", udfName));
		}
		if (functionInfo.getGenericUDF() instanceof SettableUDF) {
			// For SettableUDF, we need to pass target TypeInfo to it, but we don't have a way to do that currently.
			// Therefore just use calcite cast for these types.
			return plannerContext.getCluster().getRexBuilder().makeCast(targetCalType, srcRex);
		} else {
			return plannerContext.getCluster().getRexBuilder().makeCall(
					HiveParserSqlFunctionConverter.getCalciteOperator(
							udfName, functionInfo.getGenericUDF(), Collections.singletonList(srcRex.getType()), targetCalType),
					srcRex
			).accept(funcConverter);
		}
	}

	private RelNode replaceProjectForTypeConversion(Project project, List<RelDataType> targetCalcTypes, List<TypeInfo> targetHiveTypes)
			throws SemanticException {
		List<RexNode> exprs = project.getProjects();
		Preconditions.checkState(exprs.size() == targetCalcTypes.size(), "Expressions and target types size mismatch");
		List<RexNode> updatedExprs = new ArrayList<>(exprs.size());
		boolean updated = false;
		RexBuilder rexBuilder = plannerContext.getCluster().getRexBuilder();
		ConvertSqlFunctionCopier funcConverter = new ConvertSqlFunctionCopier(
				rexBuilder, frameworkConfig.getOperatorTable(), catalogReader.nameMatcher());
		for (int i = 0; i < exprs.size(); i++) {
			RexNode expr = exprs.get(i);
			if (expr.getType().getSqlTypeName() != targetCalcTypes.get(i).getSqlTypeName()) {
				TypeInfo hiveType = targetHiveTypes.get(i);
				RelDataType calcType = targetCalcTypes.get(i);
				// only support converting primitive types
				if (hiveType.getCategory() == ObjectInspector.Category.PRIMITIVE) {
					expr = createConversionCast(expr, (PrimitiveTypeInfo) hiveType, calcType, funcConverter);
					updated = true;
				}
			}
			updatedExprs.add(expr);
		}
		if (updated) {
			RelNode newProject = LogicalProject.create(project.getInput(), Collections.emptyList(), updatedExprs,
					getProjectNames(project));
			project.replaceInput(0, null);
			return newProject;
		} else {
			return project;
		}
	}

	private RelNode handleDestSchema(SingleRel queryRelNode, Table destTable, List<String> destSchema) throws CalciteSemanticException {
		if (destSchema == null || destSchema.isEmpty()) {
			return queryRelNode;
		}
		Preconditions.checkState(!destTable.isPartitioned(), "Dest schema for partitioned table not supported yet");
		List<FieldSchema> cols = destTable.getCols();
		// we don't need to do anything if the dest schema is the same as table schema
		if (destSchema.equals(cols.stream().map(FieldSchema::getName).collect(Collectors.toList()))) {
			return queryRelNode;
		}
		// build a list to create a Project on top of original Project
		// for each col in dest table, if it's in dest schema, store its corresponding index in the
		// dest schema, otherwise store its type and we'll create NULL for it
		List<Object> updatedIndices = new ArrayList<>(cols.size());
		for (FieldSchema col : cols) {
			int index = destSchema.indexOf(col.getName());
			if (index < 0) {
				updatedIndices.add(HiveParserTypeConverter.convert(TypeInfoUtils.getTypeInfoFromTypeString(col.getType()),
						plannerContext.getTypeFactory()));
			} else {
				updatedIndices.add(index);
			}
		}
		if (queryRelNode instanceof Project) {
			return addProjectForDestSchema((Project) queryRelNode, updatedIndices);
		} else if (queryRelNode instanceof Sort) {
			Sort sort = (Sort) queryRelNode;
			RelNode sortInput = sort.getInput();
			// DIST + LIMIT
			if (sortInput instanceof HiveDistribution) {
				RelNode newDist = handleDestSchemaForDist((HiveDistribution) sortInput, updatedIndices);
				sort.replaceInput(0, newDist);
				return sort;
			}
			// PROJECT + SORT
			RelNode addedProject = addProjectForDestSchema((Project) sortInput, updatedIndices);
			// we may need to update the field collations
			List<RelFieldCollation> fieldCollations = sort.getCollation().getFieldCollations();
			if (!fieldCollations.isEmpty()) {
				sort.replaceInput(0, null);
				sort = LogicalSort.create(addedProject,
						updateRelCollation(sort.getCollation(), updatedIndices),
						sort.offset, sort.fetch);
			}
			sort.replaceInput(0, addedProject);
			return sort;
		} else {
			// PROJECT + DIST
			return handleDestSchemaForDist((HiveDistribution) queryRelNode, updatedIndices);
		}
	}

	private RelNode handleDestSchemaForDist(HiveDistribution hiveDist, List<Object> updatedIndices) {
		Project project = (Project) hiveDist.getInput();
		RelNode addedProject = addProjectForDestSchema(project, updatedIndices);
		// disconnect the original HiveDistribution
		hiveDist.replaceInput(0, null);
		return HiveDistribution.create(
				addedProject,
				updateRelCollation(hiveDist.getCollation(), updatedIndices),
				updateDistKeys(hiveDist.getDistKeys(), updatedIndices));
	}

	private RelCollation updateRelCollation(RelCollation collation, List<Object> updatedIndices) {
		List<RelFieldCollation> fieldCollations = collation.getFieldCollations();
		if (fieldCollations.isEmpty()) {
			return collation;
		}
		List<RelFieldCollation> updatedCollations = new ArrayList<>(fieldCollations.size());
		for (RelFieldCollation fieldCollation : fieldCollations) {
			int newIndex = updatedIndices.indexOf(fieldCollation.getFieldIndex());
			Preconditions.checkState(newIndex >= 0, "Sort/Order references a non-existing field");
			fieldCollation = fieldCollation.withFieldIndex(newIndex);
			updatedCollations.add(fieldCollation);
		}
		return plannerContext.getCluster().traitSet().canonize(RelCollationImpl.of(updatedCollations));
	}

	private List<Integer> updateDistKeys(List<Integer> distKeys, List<Object> updatedIndices) {
		List<Integer> updatedDistKeys = new ArrayList<>(distKeys.size());
		for (Integer key : distKeys) {
			int newKey = updatedIndices.indexOf(key);
			Preconditions.checkState(newKey >= 0, "Cluster/Distribute references a non-existing field");
			updatedDistKeys.add(newKey);
		}
		return updatedDistKeys;
	}

	private RelNode replaceProjectForStaticPart(Project project, Map<String, String> staticPartSpec, Table destTable,
			Map<String, RelDataType> targetColToType) {
		List<RexNode> exprs = project.getProjects();
		List<RexNode> extendedExprs = new ArrayList<>(exprs);
		int numDynmPart = destTable.getTTable().getPartitionKeys().size() - staticPartSpec.size();
		int insertIndex = extendedExprs.size() - numDynmPart;
		RexBuilder rexBuilder = plannerContext.getCluster().getRexBuilder();
		for (Map.Entry<String, String> spec : staticPartSpec.entrySet()) {
			RexNode toAdd = rexBuilder.makeCharLiteral(HiveParserUtils.asUnicodeString(spec.getValue()));
			toAdd = rexBuilder.makeAbstractCast(targetColToType.get(spec.getKey()), toAdd);
			extendedExprs.add(insertIndex++, toAdd);
		}
		// TODO: we're losing the field names here, does it matter?
		RelNode res = LogicalProject.create(project.getInput(), Collections.emptyList(), extendedExprs, (List<String>) null);
		project.replaceInput(0, null);
		return res;
	}

	private static List<String> getProjectNames(Project project) {
		return project.getNamedProjects().stream().map(p -> p.right).collect(Collectors.toList());
	}

	private RelNode addProjectForDestSchema(Project input, List<Object> updatedIndices) {
		List<RexNode> exprs = new ArrayList<>(updatedIndices.size());
		RexBuilder rexBuilder = plannerContext.getCluster().getRexBuilder();
		for (Object object : updatedIndices) {
			if (object instanceof Integer) {
				exprs.add(rexBuilder.makeInputRef(input, (Integer) object));
			} else {
				RexNode rexNode = rexBuilder.makeNullLiteral((RelDataType) object);
				exprs.add(rexNode);
			}
		}
		return LogicalProject.create(input, Collections.emptyList(), exprs, (List<String>) null);
	}
}
