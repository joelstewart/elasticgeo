/**
 * This file is hereby placed into the Public Domain. This means anyone is
 * free to do whatever they wish with this file.
 */
package mil.nga.giat.data.elasticsearch;

import static org.junit.Assert.*;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import mil.nga.giat.data.elasticsearch.FilterToElastic;
import mil.nga.giat.data.elasticsearch.FilterToElasticException;
import mil.nga.giat.data.elasticsearch.ElasticAttribute.ElasticGeometryType;
import static mil.nga.giat.data.elasticsearch.ElasticLayerConfiguration.ANALYZED;
import static mil.nga.giat.data.elasticsearch.ElasticLayerConfiguration.DATE_FORMAT;
import static mil.nga.giat.data.elasticsearch.ElasticLayerConfiguration.GEOMETRY_TYPE;

import org.apache.commons.codec.binary.Base64;
import org.apache.lucene.queryparser.flexible.core.builders.QueryBuilder;
import org.elasticsearch.common.geo.ShapeRelation;
import org.elasticsearch.common.geo.builders.LineStringBuilder;
import org.elasticsearch.common.geo.builders.PolygonBuilder;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.ExistsQueryBuilder;
import org.elasticsearch.index.query.GeoBoundingBoxQueryBuilder;
import org.elasticsearch.index.query.GeoDistanceQueryBuilder;
import org.elasticsearch.index.query.GeoPolygonQueryBuilder;
import org.elasticsearch.index.query.GeoShapeQueryBuilder;
import org.elasticsearch.index.query.IdsQueryBuilder;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.MissingQueryBuilder;
import org.elasticsearch.index.query.NotQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.geotools.data.Query;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.factory.Hints;
import org.geotools.feature.AttributeTypeBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;
import org.geotools.temporal.object.DefaultInstant;
import org.geotools.temporal.object.DefaultPeriod;
import org.geotools.temporal.object.DefaultPosition;
import org.junit.Before;
import org.junit.Test;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.filter.And;
import org.opengis.filter.ExcludeFilter;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory2;
import org.opengis.filter.Id;
import org.opengis.filter.IncludeFilter;
import org.opengis.filter.Not;
import org.opengis.filter.Or;
import org.opengis.filter.PropertyIsBetween;
import org.opengis.filter.PropertyIsEqualTo;
import org.opengis.filter.PropertyIsGreaterThan;
import org.opengis.filter.PropertyIsGreaterThanOrEqualTo;
import org.opengis.filter.PropertyIsLessThan;
import org.opengis.filter.PropertyIsLessThanOrEqualTo;
import org.opengis.filter.PropertyIsLike;
import org.opengis.filter.PropertyIsNotEqualTo;
import org.opengis.filter.PropertyIsNull;
import org.opengis.filter.spatial.BBOX;
import org.opengis.filter.spatial.Beyond;
import org.opengis.filter.spatial.DWithin;
import org.opengis.filter.spatial.Intersects;
import org.opengis.filter.temporal.After;
import org.opengis.filter.temporal.Begins;
import org.opengis.filter.temporal.BegunBy;
import org.opengis.filter.temporal.During;
import org.opengis.filter.temporal.EndedBy;
import org.opengis.filter.temporal.Ends;
import org.opengis.filter.temporal.TContains;
import org.opengis.filter.temporal.TEquals;
import org.opengis.temporal.Instant;
import org.opengis.temporal.Period;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;

public class FilterToElasticTest {

    private FilterToElastic builder;

    private FilterFactory2 ff;

    private SimpleFeatureType featureType;

    private Map<String, String> parameters;

    private Query query;

    private DateFormat dateFormat;

    @Before
    public void setUp() {
        ff = CommonFactoryFinder.getFilterFactory2();

        SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
        typeBuilder.setName("test");
        typeBuilder.add("stringAttr", String.class);
        typeBuilder.add("integerAttr", Integer.class);
        typeBuilder.add("longAttr", Long.class);
        typeBuilder.add("booleanAttr", Boolean.class);
        typeBuilder.add("doubleAttr", Double.class);
        typeBuilder.add("floatAttr", Float.class);
        typeBuilder.add("dateAttr", Date.class);

        AttributeDescriptor dateAtt = null;
        AttributeTypeBuilder dateAttBuilder = new AttributeTypeBuilder();
        dateAttBuilder.setName("dateAttr2");
        dateAttBuilder.setBinding(Date.class);
        dateAtt = dateAttBuilder.buildDescriptor("dateAttr2", dateAttBuilder.buildType());
        dateAtt.getUserData().put(DATE_FORMAT, "yyyy-MM-dd");
        typeBuilder.add(dateAtt);

        AttributeDescriptor geoPointAtt = null;
        AttributeTypeBuilder geoPointAttBuilder = new AttributeTypeBuilder();
        geoPointAttBuilder.setName("geo_point");
        geoPointAttBuilder.setBinding(Point.class);
        geoPointAtt = geoPointAttBuilder.buildDescriptor("geo_point", geoPointAttBuilder.buildType());
        geoPointAtt.getUserData().put(GEOMETRY_TYPE, ElasticGeometryType.GEO_POINT);
        typeBuilder.add(geoPointAtt);

        AttributeDescriptor geoShapeAtt = null;
        AttributeTypeBuilder geoShapeAttBuilder = new AttributeTypeBuilder();
        geoShapeAttBuilder.setName("geom");
        geoShapeAttBuilder.setBinding(Geometry.class);
        geoShapeAtt = geoShapeAttBuilder.buildDescriptor("geom", geoShapeAttBuilder.buildType());
        geoShapeAtt.getUserData().put(GEOMETRY_TYPE, ElasticGeometryType.GEO_SHAPE);
        typeBuilder.add(geoShapeAtt);

        AttributeDescriptor analyzedAtt = null;
        AttributeTypeBuilder analyzedAttBuilder = new AttributeTypeBuilder();
        analyzedAttBuilder.setName("analyzed");
        analyzedAttBuilder.setBinding(String.class);
        analyzedAtt = analyzedAttBuilder.buildDescriptor("analyzed", analyzedAttBuilder.buildType());
        analyzedAtt.getUserData().put(ANALYZED, true);
        typeBuilder.add(analyzedAtt);

        featureType = typeBuilder.buildFeatureType();
        setFilterBuilder();

        parameters = new HashMap<>();
        final Hints hints = new Hints();
        hints.put(Hints.VIRTUAL_TABLE_PARAMETERS, parameters);
        query = new Query();
        query.setHints(hints);

        dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private void setFilterBuilder() {
        builder = new FilterToElastic();
        builder.setFeatureType(featureType);
    }

    @Test
    public void testId() {
        final Id filter = ff.id(ff.featureId("id"));
        IdsQueryBuilder expected = QueryBuilders.idsQuery().addIds("id");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testAnd() {
        And filter = ff.and(ff.id(ff.featureId("id1")), ff.id(ff.featureId("id2")));
        BoolQueryBuilder expected = QueryBuilders.boolQuery().must(QueryBuilders.idsQuery().addIds("id1"))
                .must(QueryBuilders.idsQuery().addIds("id2"));

        builder.visit(filter, null);

        String expe = expected.toString();
        String bld = builder.getFilterBuilder().toString();

        assertEquals(expe, bld);

        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testOr() {
        final Or filter = ff.or(ff.id(ff.featureId("id1")), ff.id(ff.featureId("id2")));
        BoolQueryBuilder expected = QueryBuilders.boolQuery().should(QueryBuilders.idsQuery().addIds("id1"))
                .should(QueryBuilders.idsQuery().addIds("id2"));

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testNot() {
        Not filter = ff.not(ff.id(ff.featureId("id")));
        BoolQueryBuilder expected = QueryBuilders.boolQuery().mustNot(QueryBuilders.idsQuery().addIds("id"));

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsNull() {
        PropertyIsNull filter = ff.isNull(ff.property("prop"));
        MissingQueryBuilder expected = QueryBuilders.missingQuery("prop");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsNotNull() {
        Not filter = ff.not(ff.isNull(ff.property("prop")));
        ExistsQueryBuilder expected = QueryBuilders.existsQuery("prop");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsEqualToString() {
        PropertyIsEqualTo filter = ff.equals(ff.property("stringAttr"), ff.literal("value"));
        TermQueryBuilder expected = QueryBuilders.termQuery("stringAttr", "value");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsNotEqualToString() {
        PropertyIsNotEqualTo filter = ff.notEqual(ff.property("stringAttr"), ff.literal("value"));
        BoolQueryBuilder expected = QueryBuilders.boolQuery().mustNot(QueryBuilders.termQuery("stringAttr", "value"));

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsEqualToDouble() {
        PropertyIsEqualTo filter = ff.equals(ff.property("doubleAttr"), ff.literal("4.5"));
        TermQueryBuilder expected = QueryBuilders.termQuery("doubleAttr", 4.5);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsNotEqualToDouble() {
        PropertyIsNotEqualTo filter = ff.notEqual(ff.property("doubleAttr"), ff.literal("4.5"));
        BoolQueryBuilder expected = QueryBuilders.boolQuery().mustNot(QueryBuilders.termQuery("doubleAttr", 4.5));

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsEqualToFloat() {
        PropertyIsEqualTo filter = ff.equals(ff.property("floatAttr"), ff.literal("4.5"));
        TermQueryBuilder expected = QueryBuilders.termQuery("floatAttr", 4.5f);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsEqualToInteger() {
        PropertyIsEqualTo filter = ff.equals(ff.property("integerAttr"), ff.literal("4"));
        TermQueryBuilder expected = QueryBuilders.termQuery("integerAttr", 4);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsEqualToBoolean() {
        PropertyIsEqualTo filter = ff.equals(ff.property("booleanAttr"), ff.literal("true"));
        TermQueryBuilder expected = QueryBuilders.termQuery("booleanAttr", true);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsGreaterThan() {
        PropertyIsGreaterThan filter = ff.greater(ff.property("doubleAttr"), ff.literal("4.5"));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("doubleAttr").gt(4.5);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsLessThan() {
        PropertyIsLessThan filter = ff.less(ff.property("doubleAttr"), ff.literal("4.5"));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("doubleAttr").lt(4.5);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsGreaterThanOrEqualTo() {
        PropertyIsGreaterThanOrEqualTo filter = ff.greaterOrEqual(ff.property("doubleAttr"), ff.literal("4.5"));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("doubleAttr").gte(4.5);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsLessThanOrEqualTo() {
        PropertyIsLessThanOrEqualTo filter = ff.lessOrEqual(ff.property("doubleAttr"), ff.literal("4.5"));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("doubleAttr").lte(4.5);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsBetween() {
        PropertyIsBetween filter = ff.between(ff.property("doubleAttr"), ff.literal("4.5"), ff.literal("5.5"));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("doubleAttr").gte(4.5).lte(5.5);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testIncludeFilter() {
        IncludeFilter filter = Filter.INCLUDE;
        MatchAllQueryBuilder expected = QueryBuilders.matchAllQuery();

        builder.visit(filter, null);

        String exp = expected.toString();
        String act = builder.getFilterBuilder().toString();
        assertEquals(exp, act);

        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testExcludeFilter() {
        ExcludeFilter filter = Filter.EXCLUDE;
        BoolQueryBuilder expected = QueryBuilders.boolQuery().mustNot(QueryBuilders.matchAllQuery());

        builder.visit(filter, null);

        String exp = expected.toString();
        String act = builder.getFilterBuilder().toString();
        assertEquals(exp, act);

        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testPropertyIsLike() {
        PropertyIsLike filter = ff.like(ff.property("analyzed"), "hello");
        QueryStringQueryBuilder expected = QueryBuilders.queryStringQuery("hello").defaultField("analyzed");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testConvertToQueryString() {
        assertTrue("BroadWay*".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "BroadWay*")));
        assertTrue("broad#ay".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "broad#ay")));
        assertTrue("broadway".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "broadway")));

        assertTrue("broad?ay".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "broad.ay")));
        assertTrue("broad\\.ay".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "broad!.ay")));

        assertTrue("broa'dway".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "broa'dway")));
        assertTrue("broa''dway".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "broa''dway")));

        assertTrue("broadway?".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "broadway.")));
        assertTrue("broadway".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "broadway!")));
        assertTrue("broadway\\!".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "broadway!!")));
        assertTrue("broadway\\\\".equals(FilterToElastic.convertToQueryString('\\', '*', '.', true, "broadway\\\\")));
        assertTrue("broadway\\".equals(FilterToElastic.convertToQueryString('!', '*', '.', true, "broadway\\")));
    }

    @Test
    public void testGeoShapeBboxFilter() throws ParseException, IOException {
        BBOX filter = ff.bbox("geom", 0., 0., 1., 1., "EPSG:4326");
        PolygonBuilder shape = ShapeBuilder.newPolygon().point(0, 0).point(0, 1).point(1, 1).point(1, 0).point(0, 0);
        GeoShapeQueryBuilder expected = QueryBuilders.geoShapeQuery("geom", shape, ShapeRelation.INTERSECTS);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testGeoShapeIntersectsFilter() throws CQLException {
        Intersects filter = (Intersects) ECQL.toFilter("INTERSECTS(\"geom\", LINESTRING(0 0,1 1))");
        LineStringBuilder shape = ShapeBuilder.newLineString().point(0, 0).point(1, 1);
        GeoShapeQueryBuilder expected = QueryBuilders.geoShapeQuery("geom", shape, ShapeRelation.INTERSECTS);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testAndWithBbox() {
        And filter = ff.and(ff.id(ff.featureId("id1")), ff.bbox("geom", 0., 0., 1., 1., "EPSG:4326"));
        PolygonBuilder shape = ShapeBuilder.newPolygon().point(0, 0).point(0, 1).point(1, 1).point(1, 0).point(0, 0);
        BoolQueryBuilder expected = QueryBuilders.boolQuery().must(QueryBuilders.idsQuery().addIds("id1"))
                .must(QueryBuilders.geoShapeQuery("geom", shape, ShapeRelation.INTERSECTS));

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testGeoPointBboxFilter() {
        BBOX filter = ff.bbox("geo_point", 0., 0., 1., 1., "EPSG:4326");
        GeoBoundingBoxQueryBuilder expected = QueryBuilders.geoBoundingBoxQuery("geo_point").topLeft(1, 0)
                .bottomRight(0, 1);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testGeoPolygonFilter() throws CQLException {
        Intersects filter = (Intersects) ECQL.toFilter("INTERSECTS(\"geo_point\", POLYGON((0 0, 0 1, 1 1, 1 0, 0 0)))");
        GeoPolygonQueryBuilder expected = QueryBuilders.geoPolygonQuery("geo_point").addPoint(0, 0).addPoint(1, 0)
                .addPoint(1, 1).addPoint(0, 1).addPoint(0, 0);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testDWithinFilter() throws CQLException {
        DWithin filter = (DWithin) ECQL.toFilter("DWITHIN(\"geo_point\", POINT(0 1), 1.0, meters)");
        GeoDistanceQueryBuilder expected = QueryBuilders.geoDistanceQuery("geo_point").lat(1).lon(0).distance(1.,
                DistanceUnit.METERS);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testDWithinPolygonFilter() throws CQLException {
        DWithin filter = (DWithin) ECQL
                .toFilter("DWITHIN(\"geo_point\", POLYGON((0 0, 0 1, 1 1, 1 0, 0 0)), 1.0, meters)");
        GeoDistanceQueryBuilder expected = QueryBuilders.geoDistanceQuery("geo_point").lat(0.5).lon(0.5).distance(1.,
                DistanceUnit.METERS);

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testDBeyondFilter() throws CQLException {
        Beyond filter = (Beyond) ECQL.toFilter("BEYOND(\"geo_point\", POINT(0 1), 1.0, meters)");
        BoolQueryBuilder expected = QueryBuilders.boolQuery()
                .mustNot(QueryBuilders.geoDistanceQuery("geo_point").lat(1).lon(0).distance(1., DistanceUnit.METERS));

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void compoundFilter() throws CQLException, FilterToElasticException {
        Filter filter = ECQL.toFilter("time > \"1970-01-01\" and INTERSECTS(\"geom\", LINESTRING(0 0,1 1))");
        RangeQueryBuilder expected1 = QueryBuilders.rangeQuery("time").gt("1970-01-01");
        LineStringBuilder shape = ShapeBuilder.newLineString().point(0, 0).point(1, 1);
        GeoShapeQueryBuilder expected2 = QueryBuilders.geoShapeQuery("geom", shape, ShapeRelation.INTERSECTS);
        BoolQueryBuilder expected = QueryBuilders.boolQuery().must(expected1).must(expected2);

        builder.encode(filter);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));

        String one = expected.toString();
        String two = builder.getFilterBuilder().toString();

        assertEquals(one, two);
    }

    @Test
    public void testCql() throws CQLException, FilterToElasticException {
        Filter filter = ECQL.toFilter("\"object.field\"='value'");
        TermQueryBuilder expected = QueryBuilders.termQuery("object.field", "value");

        builder.encode(filter);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testQueryViewParam() {
        IdsQueryBuilder idsQuery = QueryBuilders.idsQuery("type1");
        parameters.put("q", idsQuery.toString());
        byte[] encoded = Base64.encodeBase64(idsQuery.toString().getBytes());
        final Pattern expected = Pattern.compile(".*\"wrapper\".*\"query\".*\"" + new String(encoded) + ".*",
                Pattern.MULTILINE | Pattern.DOTALL);

        builder.addViewParams(query);
        assertTrue(builder.getFilterBuilder().toString().equals(QueryBuilders.matchAllQuery().toString()));
        assertTrue(expected.matcher(builder.getQueryBuilder().toString()).matches());
    }

    @Test
    public void testFilterViewParam() {
        IdsQueryBuilder idsFilter = QueryBuilders.idsQuery().addIds("id");
        parameters.put("f", idsFilter.toString());
        byte[] encoded = Base64.encodeBase64(idsFilter.toString().getBytes());
        final Pattern expected = Pattern.compile(".*\"wrapper\".*\"query\".*\"" + new String(encoded) + ".*",
                Pattern.MULTILINE | Pattern.DOTALL);

        builder.addViewParams(query);

        String exp = builder.getFilterBuilder().toString();

        assertTrue(expected.matcher(builder.getFilterBuilder().toString()).matches());
        assertTrue(builder.getQueryBuilder().toString().equals(QueryBuilders.matchAllQuery().toString()));
    }

    @Test
    public void testAndFilterViewParam() {
        IdsQueryBuilder idsFilter = QueryBuilders.idsQuery().addIds("id");
        builder.filterBuilder = idsFilter;
        parameters.put("f", idsFilter.toString());

        builder.addViewParams(query);
        assertTrue(builder.getFilterBuilder() instanceof BoolQueryBuilder);
    }

    @Test
    public void testTemporalStringLiteral() {
        After filter = ff.after(ff.property("dateAttr"), ff.literal("1970-01-01 00:00:00"));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").gt("1970-01-01 00:00:00");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testTemporalInstantLiteralDefaultFormat() throws ParseException {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date date1 = dateFormat.parse("1970-07-19");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        After filter = ff.after(ff.property("dateAttr"), ff.literal(temporalInstant));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").gt("1970-07-19T00:00:00.000Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testTemporalInstanceLiteralExplicitFormat() throws ParseException {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456-0100");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        After filter = ff.after(ff.property("dateAttr2"), ff.literal(temporalInstant));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr2").gt("1970-07-19");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testAfterFilter() throws ParseException {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456-0100");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        After filter = ff.after(ff.property("dateAttr"), ff.literal(temporalInstant));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").gt("1970-07-19T02:02:03.456Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testAfterFilterSwapped() throws ParseException {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456-0100");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        After filter = ff.after(ff.literal(temporalInstant), ff.property("dateAttr"));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").lt("1970-07-19T02:02:03.456Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testAfterFilterPeriod() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        Date date2 = dateFormat.parse("1970-07-19T07:08:09.101Z");
        Instant temporalInstant2 = new DefaultInstant(new DefaultPosition(date2));
        Period period = new DefaultPeriod(temporalInstant, temporalInstant2);
        After filter = ff.after(ff.property("dateAttr"), ff.literal(period));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").gt("1970-07-19T07:08:09.101Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testAfterFilterPeriodSwapped() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        Date date2 = dateFormat.parse("1970-07-19T07:08:09.101Z");
        Instant temporalInstant2 = new DefaultInstant(new DefaultPosition(date2));
        Period period = new DefaultPeriod(temporalInstant, temporalInstant2);
        After filter = ff.after(ff.literal(period), ff.property("dateAttr"));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").lt("1970-07-19T01:02:03.456Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testBeforeFilter() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        org.opengis.filter.temporal.Before filter = ff.before(ff.property("dateAttr"), ff.literal(temporalInstant));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").lt("1970-07-19T01:02:03.456Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testBeforeFilterPeriod() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        Date date2 = dateFormat.parse("1970-07-19T07:08:09.101Z");
        Instant temporalInstant2 = new DefaultInstant(new DefaultPosition(date2));
        Period period = new DefaultPeriod(temporalInstant, temporalInstant2);

        org.opengis.filter.temporal.Before filter = ff.before(ff.property("dateAttr"), ff.literal(period));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").lt("1970-07-19T01:02:03.456Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testBeforeFilterPeriodSwapped() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        Date date2 = dateFormat.parse("1970-07-19T07:08:09.101Z");
        Instant temporalInstant2 = new DefaultInstant(new DefaultPosition(date2));
        Period period = new DefaultPeriod(temporalInstant, temporalInstant2);

        org.opengis.filter.temporal.Before filter = ff.before(ff.literal(period), ff.property("dateAttr"));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").gt("1970-07-19T07:08:09.101Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testBegins() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        Date date2 = dateFormat.parse("1970-07-19T07:08:09.101Z");
        Instant temporalInstant2 = new DefaultInstant(new DefaultPosition(date2));
        Period period = new DefaultPeriod(temporalInstant, temporalInstant2);

        Begins filter = ff.begins(ff.property("dateAttr"), ff.literal(period));
        TermQueryBuilder expected = QueryBuilders.termQuery("dateAttr", "1970-07-19T01:02:03.456Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testBegunBy() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        Date date2 = dateFormat.parse("1970-07-19T07:08:09.101Z");
        Instant temporalInstant2 = new DefaultInstant(new DefaultPosition(date2));
        Period period = new DefaultPeriod(temporalInstant, temporalInstant2);

        BegunBy filter = ff.begunBy(ff.literal(period), ff.property("dateAttr"));
        TermQueryBuilder expected = QueryBuilders.termQuery("dateAttr", "1970-07-19T01:02:03.456Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testDuring() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        Date date2 = dateFormat.parse("1970-07-19T07:08:09.101Z");
        Instant temporalInstant2 = new DefaultInstant(new DefaultPosition(date2));
        Period period = new DefaultPeriod(temporalInstant, temporalInstant2);

        During filter = ff.during(ff.property("dateAttr"), ff.literal(period));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").gt("1970-07-19T01:02:03.456Z")
                .lt("1970-07-19T07:08:09.101Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testEnds() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        Date date2 = dateFormat.parse("1970-07-19T07:08:09.101Z");
        Instant temporalInstant2 = new DefaultInstant(new DefaultPosition(date2));
        Period period = new DefaultPeriod(temporalInstant, temporalInstant2);

        Ends filter = ff.ends(ff.property("dateAttr"), ff.literal(period));
        TermQueryBuilder expected = QueryBuilders.termQuery("dateAttr", "1970-07-19T07:08:09.101Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testEndedBy() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        Date date2 = dateFormat.parse("1970-07-19T07:08:09.101Z");
        Instant temporalInstant2 = new DefaultInstant(new DefaultPosition(date2));
        Period period = new DefaultPeriod(temporalInstant, temporalInstant2);

        EndedBy filter = ff.endedBy(ff.literal(period), ff.property("dateAttr"));
        TermQueryBuilder expected = QueryBuilders.termQuery("dateAttr", "1970-07-19T07:08:09.101Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testTContains() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        Date date2 = dateFormat.parse("1970-07-19T07:08:09.101Z");
        Instant temporalInstant2 = new DefaultInstant(new DefaultPosition(date2));
        Period period = new DefaultPeriod(temporalInstant, temporalInstant2);

        TContains filter = ff.tcontains(ff.literal(period), ff.property("dateAttr"));
        RangeQueryBuilder expected = QueryBuilders.rangeQuery("dateAttr").gt("1970-07-19T01:02:03.456Z")
                .lt("1970-07-19T07:08:09.101Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }

    @Test
    public void testTEqualsFilter() throws ParseException {
        Date date1 = dateFormat.parse("1970-07-19T01:02:03.456Z");
        Instant temporalInstant = new DefaultInstant(new DefaultPosition(date1));
        TEquals filter = ff.tequals(ff.property("dateAttr"), ff.literal(temporalInstant));
        TermQueryBuilder expected = QueryBuilders.termQuery("dateAttr", "1970-07-19T01:02:03.456Z");

        builder.visit(filter, null);
        assertTrue(builder.createFilterCapabilities().fullySupports(filter));
        assertTrue(builder.getFilterBuilder().toString().equals(expected.toString()));
    }
}
