/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.roi;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.awt.common.AwtTools;
import qupath.lib.geom.ImmutableDimension;
import qupath.lib.geom.Point2;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.EllipseROI;
import qupath.lib.roi.LineROI;
import qupath.lib.roi.AreaROI;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.PathArea;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.roi.interfaces.PathShape;

/**
 * A collection of static methods for working with ROIs.
 * 
 * @author Pete Bankhead
 *
 */
public class RoiTools {

	private final static Logger logger = LoggerFactory.getLogger(RoiTools.class);

	/**
	 * Methods of combining two ROIs.
	 */
	public enum CombineOp {
		/**
		 * Add ROIs (union).
		 */
		ADD,
		
		/**
		 * Subtract from first ROI.
		 */
		SUBTRACT,
		
		/**
		 * Calculate intersection (overlap) between ROIs.
		 */
		INTERSECT
		}//, XOR}

	/**
	 * Combine two shape ROIs together.
	 * 
	 * @param shape1
	 * @param shape2
	 * @param op
	 * @return
	 */
	public static PathShape combineROIs(PathShape shape1, PathShape shape2, CombineOp op) {
		// Check we can combine
		if (!RoiTools.sameImagePlane(shape1, shape2))
			throw new IllegalArgumentException("Cannot combine - shapes " + shape1 + " and " + shape2 + " do not share the same image plane");
		var area1 = shape1.getGeometry();
		var area2 = shape2.getGeometry();
		
		// Do a quick check to see if a combination might be avoided
		switch (op) {
		case ADD:
			return (PathShape)GeometryTools.convertGeometryToROI(area1.union(area2), shape1.getImagePlane());
		case INTERSECT:
			return (PathShape)GeometryTools.convertGeometryToROI(area1.intersection(area2), shape1.getImagePlane());
		case SUBTRACT:
			return (PathShape)GeometryTools.convertGeometryToROI(area1.difference(area2), shape1.getImagePlane());
		default:
			throw new IllegalArgumentException("Unknown op " + op);
		}
	}

//	/**
//	 * Compute two shape ROIs together, using the specified 'flatness' to handle curved segments.
//	 * 
//	 * @param shape1
//	 * @param shape2
//	 * @param op
//	 * @param flatness
//	 * @return
//	 */
//	public static PathShape combineROIs(PathShape shape1, PathShape shape2, CombineOp op, double flatness) {
//		// Check we can combine
//		if (!RoiTools.sameImagePlane(shape1, shape2))
//			throw new IllegalArgumentException("Cannot combine - shapes " + shape1 + " and " + shape2 + " do not share the same image plane");
//		Area area1 = getArea(shape1);
//		Area area2 = getArea(shape2);
//		
//		// Do a quick check to see if a combination might be avoided
//		if (op == CombineOp.INTERSECT) {
//			if (area1.contains(area2.getBounds2D()))
//				return shape2;
//			if (area2.contains(area1.getBounds2D()))
//				return shape1;
//		} else if (op == CombineOp.ADD) {
//			if (area1.contains(area2.getBounds2D()))
//				return shape1;
//			if (area2.contains(area1.getBounds2D()))
//				return shape2;			
//		}
//		
//		combineAreas(area1, area2, op);
//		// I realise the following looks redundant... however direct use of the areas with the
//		// brush tool led to strange artefacts appearing & disappearing... performing an additional
//		// conversion seems to help
//		//		area1 = new Area(new Path2D.Float(area1));
//		// Return simplest ROI that works - prefer a rectangle or polygon over an area
//		return getShapeROI(area1, shape1.getImagePlane(), flatness);
//	}
	
	/**
	 * Fill the holes of an {@link AreaROI}, or return the ROI unchanged if it contains no holes.
	 * 
	 * @param roi
	 * @return
	 */
	public static ROI fillHoles(ROI roi) {
		if (roi instanceof AreaROI)
			return removeSmallPieces((AreaROI)roi, 0, Double.POSITIVE_INFINITY);
		return roi;
	}

	/**
	 * Remove small pieces from a {@link ROI}.
	 * <p>
	 * If the ROI is an {@link AreaROI} this may be fragments that leave the ROI itself 
	 * otherwise intact.  For other ROIs, if the area is &gt; minArea it will be returned, 
	 * otherwise an empty ROI will be returned instead.
	 * 
	 * @param roi
	 * @param minArea
	 * @return
	 */
	static ROI removeSmallPieces(ROI roi, double minArea) {
		if (roi instanceof AreaROI || roi instanceof PolygonROI)
			return removeSmallPieces((AreaROI)roi, minArea, -1);
		if (roi instanceof PathArea && ((PathArea)roi).getArea() > minArea)
			return roi;
		return ROIs.createEmptyROI();
	}

	/**
	 * Remove small pieces and fill small holes of an {@link AreaROI}.
	 * 
	 * @param roi
	 * @param minArea
	 * @param maxHoleArea
	 * @return
	 */
	public static ROI removeSmallPieces(ROI roi, double minArea, double maxHoleArea) {
		if (!roi.isArea())
			throw new IllegalArgumentException("Only PathArea ROIs supported!");
		
		// We can't have holes if we don't have an AreaROI
		if (!(roi instanceof AreaROI || roi instanceof PolygonROI)) {
			return removeSmallPieces(roi, minArea);
		}
		
		AreaROI areaROI;
		if (roi instanceof AreaROI)
			areaROI = (AreaROI)roi;
		else
			areaROI = (AreaROI)ROIs.createAreaROI(roi.getShape(), ImagePlane.getPlane(roi));
		
		var polygons = splitAreaToPolygons(areaROI);
		
		// Keep track of whether we are filtering out any pieces; if not, return the original ROI
		boolean changes = false;
		
		var path = new Path2D.Double(Path2D.WIND_NON_ZERO);
		for (var poly : polygons[1]) {
			if (minArea <= 0 || poly.getArea() > minArea) {
				var points = poly.getPolygonPoints();
				var p = points.get(0);
				path.moveTo(p.getX(), p.getY());
				for (int i = 1; i < points.size(); i++) {
					p = points.get(i);
					path.lineTo(p.getX(), p.getY());
				}
				path.closePath();
			} else
				changes = true;
		}
	
		for (var poly : polygons[0]) {
			if (maxHoleArea <= 0 || poly.getArea() > maxHoleArea) {
				var points = poly.getPolygonPoints();
				var p = points.get(0);
				path.moveTo(p.getX(), p.getY());
				for (int i = 1; i < points.size(); i++) {
					p = points.get(i);
					path.lineTo(p.getX(), p.getY());
				}
				path.closePath();
			} else
				changes = true;
		}
		if (changes)
			return getShapeROI(new Area(path), ImagePlane.getPlane(roi), 0.5);
		else
			return roi;
	}

	
	/**
	 * Compute two Areas together, modifying the first.
	 * 
	 * @param area1 the primary Area, which will be modified by this operation
	 * @param area2 the secondary Area, which will be unchanged
	 * @param op method of combination
	 */
	public static void combineAreas(Area area1, Area area2, CombineOp op) {
		switch (op) {
		case ADD:
			area1.add(area2);
			break;
		case SUBTRACT:
			area1.subtract(area2);
			break;
		case INTERSECT:
			area1.intersect(area2);
			break;
			//		case XOR:
			//			area1.exclusiveOr(area2);
			//			break;
		default:
			throw new IllegalArgumentException("Invalid CombineOp " + op);
		}
	}


	/**
	 * Get a PathShape from an Area.
	 * This will try to return a RectangleROI or PolygonROI if possible,
	 * or AreaROI if neither of the other classes can adequately represent the area.
	 * 
	 * @param area
	 * @param plane
	 * @param flatness - can be used to prefer polygons, see Shape.getPathIterator(AffineTransform at, double flatness)
	 * @return
	 */
	static PathShape getShapeROI(Area area, ImagePlane plane, double flatness) {
		if (area.isRectangular()) {
			Rectangle2D bounds = area.getBounds2D();
			return new RectangleROI(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(), plane);
		}
		//		else if (area.isPolygonal() && area.isSingular())
		else if (area.isSingular() && (area.isPolygonal() || flatness > 0)) {
			Path2D path = new Path2D.Float(area);
			List<Point2> points = flatness > 0 ? RoiTools.getLinearPathPoints(path, path.getPathIterator(null, flatness)) : RoiTools.getLinearPathPoints(path, path.getPathIterator(null));
			return new PolygonROI(points, plane);
			//			if (area.isPolygonal())
			//				return new PolygonROI(new Path2D.Float(area), c, z, t);
			//			else if (flatness > 0) {
			//				Path2D path = new Path2D.Float();
			//				path.append(area.getPathIterator(null, flatness), false);
			//				return new PolygonROI(path, c, z, t);
			//			}
		}
		return ROIs.createAreaROI(area, plane);		
	}

	/**
	 * Create a {@link PathShape} from an Shape with a specified 'flatness'.
	 * This will try to return a RectangleROI or PolygonROI if possible,
	 * or AreaROI if neither of the other classes can adequately represent the area.
	 * 
	 * In the input shape is an Ellipse2D then an EllipseROI will be returned.
	 * 
	 * @param shape
	 * @param plane
	 * @param flatness - can be used to prefer polygons, see Shape.getPathIterator(AffineTransform at, double flatness)
	 * @return
	 */
	public static PathShape getShapeROI(Shape shape, ImagePlane plane, double flatness) {
		if (shape instanceof Rectangle2D) {
			Rectangle2D bounds = shape.getBounds2D();
			return new RectangleROI(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(), plane);
		}
		if (shape instanceof Ellipse2D) {
			Rectangle2D bounds = shape.getBounds2D();
			return new EllipseROI(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(), plane);
		}
		if (shape instanceof Line2D) {
			Line2D line = (Line2D)shape;
			return new LineROI(line.getX1(), line.getY1(), line.getX2(), line.getY2(), plane);
		}
		boolean isClosed = false;
		List<Point2> points = null;
		if (!(shape instanceof Area)) {
			PathIterator iterator = shape.getPathIterator(null, flatness);
			double[] coords = new double[6];
			points = new ArrayList<>();
			while (!iterator.isDone()) {
				int type = iterator.currentSegment(coords);
				if (type == PathIterator.SEG_CLOSE) {
					isClosed = true;
					break;
				} else
					points.add(new Point2(coords[0], coords[1]));
				iterator.next();
			}
		}
		
		// Handle closed shapes via Area objects, as this gives more options to simplify 
		// (e.g. by checking isRectangular, isPolygonal)
		if (isClosed) {
			Area area;
			if (shape instanceof Area) {
				area = (Area)shape;
			} else
				area = new Area(shape);
			return getShapeROI(area, plane, flatness);
		} else if (points.size() == 2) {
			// Handle straight lines, with only two end points
			Point2 p1 = points.get(0);
			Point2 p2 = points.get(1);
			return ROIs.createLineROI(p1.getX(), p1.getY(), p2.getX(), p2.getY(), plane);
		} else
			// Handle polylines
			return new PolylineROI(points, plane);
	}


	/**
	 * Create a {@link PathShape} from an Shape.
	 * This will try to return a RectangleROI or PolygonROI if possible,
	 * or AreaROI if neither of the other classes can adequately represent the area.
	 * 
	 * In the input shape is an Ellipse2D then an EllipseROI will be returned.
	 * 
	 * @param area
	 * @param plane
	 * @return
	 */
	public static PathShape getShapeROI(Area area, ImagePlane plane) {
		return getShapeROI(area, plane, -1);
	}




	/**
	 * Get a java.awt.Shape object representing a ROI.
	 * 
	 * @param roi
	 * @return
	 */
	public static Shape getShape(final ROI roi) {

		if (roi instanceof RectangleROI)
			return new Rectangle2D.Float((float)roi.getBoundsX(), (float)roi.getBoundsY(), (float)roi.getBoundsWidth(), (float)roi.getBoundsHeight());

		if (roi instanceof EllipseROI)
			return new Ellipse2D.Float((float)roi.getBoundsX(), (float)roi.getBoundsY(), (float)roi.getBoundsWidth(), (float)roi.getBoundsHeight());

		if (roi instanceof LineROI) {
			LineROI line = (LineROI)roi;
			return new Line2D.Float((float)line.getX1(), (float)line.getY1(), (float)line.getX2(), (float)line.getY2());
		}

		if (roi instanceof PolygonROI) {
			PolygonROI polygon = (PolygonROI)roi;
			Path2D path = new Path2D.Float();
			Vertices vertices = polygon.getVertices();
			for (int i = 0; i <  vertices.size(); i++) {
				if (i == 0)
					path.moveTo(vertices.getX(i), vertices.getY(i));
				else
					path.lineTo(vertices.getX(i), vertices.getY(i));
			}
			path.closePath();
			return path;
		}
		
		if (roi instanceof PolylineROI) {
			PolylineROI polygon = (PolylineROI)roi;
			Path2D path = new Path2D.Float();
			Vertices vertices = polygon.getVertices();
			for (int i = 0; i <  vertices.size(); i++) {
				if (i == 0)
					path.moveTo(vertices.getX(i), vertices.getY(i));
				else
					path.lineTo(vertices.getX(i), vertices.getY(i));
			}
			return path;
		}

		//		if (roi instanceof PolygonROI) {
		//			PolygonROI polygon = (PolygonROI)roi;
		//			Path2D path = new Path2D.Float();
		//			boolean firstPoint = true;
		//			for (Point2 p : polygon.getPolygonPoints()) {
		//				if (firstPoint) {
		//					path.moveTo(p.getX(), p.getY());
		//					firstPoint = false;
		//				}
		//				else {
		//					path.lineTo(p.getX(), p.getY());
		//				}
		//			}
		//			path.closePath();
		//			return path;
		//		}

		//		if (roi instanceof PolygonROI) {
		//			PolygonROI polygon = (PolygonROI)roi;
		//			VerticesIterator iterator = polygon.getVerticesIterator();
		//			Path2D path = new Path2D.Float();
		//			boolean firstPoint = true;
		//			while (iterator.hasNext()) {
		//				if (firstPoint) {
		//					path.moveTo(iterator.getX(), iterator.getY());
		//					firstPoint = false;
		//				}
		//				else {
		//					path.lineTo(iterator.getX(), iterator.getY());
		//				}
		//				iterator.next();
		//			}
		//			path.closePath();
		//			return path;
		//		}

		if (roi instanceof AWTAreaROI || roi instanceof GeometryROI) {
			return roi.getShape();
		}
		if (roi instanceof AreaROI) {
			return new AWTAreaROI((AreaROI)roi).getShape();
		}

		throw new IllegalArgumentException(roi + " cannot be converted to a shape!");
	}


	/**
	 * Get a java.awt.geom.Area object representing a ROI.
	 * 
	 * @param roi
	 * @return
	 */
	public static Area getArea(final ROI roi) {
		Shape shape = getShape(roi);
		if (shape instanceof Area)
			return (Area)shape;
		return new Area(shape);

	}

	
	/**
	 * Make fixed-size rectangular tile ROIs for a specified area.
	 * 
	 * @param roi area to be tiled
	 * @param tileWidth requested tile width, in pixels
	 * @param tileHeight requested tile height, in pixels
	 * @param trimToROI if true, trim tiles at the ROI boundary according to the ROI shape, otherwise retain full tiles that may only partially overlap
	 * @return
	 */
	public static List<ROI> makeTiles(final PathArea roi, final int tileWidth, final int tileHeight, final boolean trimToROI) {
		// Create a collection of tiles
		Rectangle bounds = AwtTools.getBounds(roi);
		Area area = getArea(roi);
		List<ROI> tiles = new ArrayList<>();
		//		int ind = 0;
		for (int y = bounds.y; y < bounds.y + bounds.height; y += tileHeight) {
			for (int x = bounds.x; x < bounds.x + bounds.width; x += tileWidth) {
				//				int width = Math.min(x + tileWidth, bounds.x + bounds.width) - x;
				//				int height = Math.min(y + tileHeight, bounds.y + bounds.height) - y;
				int width = tileWidth;
				int height = tileHeight;
				Rectangle tileBounds = new Rectangle(x, y, width, height);
				ROI tile;
				// If the tile is completely contained by the ROI, it's straightforward
				if (area.contains(x, y, width, height))
					tile = new RectangleROI(x, y, width, height);
				else if (!trimToROI) {
					// If we aren't trimming, then check if the centroid is contained
					if (area.contains(x+0.5*width, y+0.5*height))
						tile = new RectangleROI(x, y, width, height);
					else
						continue;
				}
				else {
					// Check if we are actually within the object
					if (!area.intersects(x, y, width, height))
						continue;
					// Shrink the tile if that is sensible
					// TODO: Avoid converting tiles to Areas where not essential
					Area tileArea = new Area(tileBounds);
					tileArea.intersect(area);
					if (tileArea.isEmpty())
						continue;
					if (tileArea.isRectangular()) {
						Rectangle2D bounds2 = tileArea.getBounds2D();
						tile = new RectangleROI(bounds2.getX(), bounds2.getY(), bounds2.getWidth(), bounds2.getHeight());
					}
					else
						tile = ROIs.createAreaROI(tileArea, roi.getImagePlane());
				}
				//				tile.setName("Tile " + (++ind));
				tiles.add(tile);
			}			
		}
		return tiles;
	}
	
	
	
	/**
	 * Create a collection of tiled ROIs corresponding to a specified parentROI if it is larger than sizeMax, with optional overlaps.
	 * <p>
	 * The purpose of this is to create useful tiles whenever the exact tile size may not be essential, and overlaps may be required.
	 * Tiles at the parentROI boundary will be trimmed to fit inside. If the parentROI is smaller, it is returned as is.
	 * 
	 * @param parentROI main ROI to be tiled
	 * @param sizePreferred the preferred size; in general tiles should have this size
	 * @param sizeMax the maximum allowed size; occasionally it is more efficient to have a tile larger than the preferred size towards a ROI boundary to avoid creating very small tiles unnecessarily
	 * @param fixedSize if true, the tile size is enforced so that complete tiles have the same size
	 * @param overlap optional requested overlap between tiles
	 * @return
	 * 
	 * @see #makeTiles(PathArea, int, int, boolean)
	 */
	public static Collection<? extends ROI> computeTiledROIs(ROI parentROI, ImmutableDimension sizePreferred, ImmutableDimension sizeMax, boolean fixedSize, int overlap) {

		PathArea pathArea = parentROI instanceof PathArea ? (PathArea)parentROI : null;
		Rectangle2D bounds = AwtTools.getBounds2D(parentROI);
		if (pathArea == null || (bounds.getWidth() <= sizeMax.width && bounds.getHeight() <= sizeMax.height)) {
			return Collections.singletonList(parentROI);
		}


		List<ROI> pathROIs = new ArrayList<>();

		Area area = getArea(pathArea);

		double xMin = bounds.getMinX();
		double yMin = bounds.getMinY();
		int nx = (int)Math.ceil(bounds.getWidth() / sizePreferred.width);
		int ny = (int)Math.ceil(bounds.getHeight() / sizePreferred.height);
		double w = fixedSize ? sizePreferred.width : (int)Math.ceil(bounds.getWidth() / nx);
		double h = fixedSize ? sizePreferred.height : (int)Math.ceil(bounds.getHeight() / ny);

		// Center the tiles
		xMin = (int)(bounds.getCenterX() - (nx * w * .5));
		yMin = (int)(bounds.getCenterY() - (ny * h * .5));

		for (int yi = 0; yi < ny; yi++) {
			for (int xi = 0; xi < nx; xi++) {

				double x = xMin + xi * w - overlap;
				double y = yMin + yi * h - overlap;

				Rectangle2D boundsTile = new Rectangle2D.Double(x, y, w + overlap*2, h + overlap*2);

				//				double x = xMin + xi * w;
				//				double y = yMin + yi * h;
				//				
				//				Rectangle2D boundsTile = new Rectangle2D.Double(x, y, w, h);
				//					logger.info(boundsTile);
				ROI pathROI = null;
				Shape shape = getShape(pathArea);
				if (shape.contains(boundsTile))
					pathROI = new RectangleROI(boundsTile.getX(), boundsTile.getY(), boundsTile.getWidth(), boundsTile.getHeight(), parentROI.getImagePlane());
				else if (pathArea instanceof RectangleROI) {
					Rectangle2D bounds2 = boundsTile.createIntersection(bounds);
					pathROI = new RectangleROI(bounds2.getX(), bounds2.getY(), bounds2.getWidth(), bounds2.getHeight(), parentROI.getImagePlane());
				}
				else {
					if (!area.intersects(boundsTile))
						continue;
					Area areaTemp = new Area(boundsTile);
					areaTemp.intersect(area);
					if (!areaTemp.isEmpty())
						pathROI = ROIs.createAreaROI(areaTemp, parentROI.getImagePlane());					
				}
				if (pathROI != null)
					pathROIs.add(pathROI);
				x += w;
			}
		}
		return pathROIs;
	}
	
	
	/**
	 * Split a multi-part ROI into separate pieces.
	 * <p>
	 * If the ROI is already a distinct, single region or line it is returned as a singleton list.
	 * 
	 * @param roi
	 * @return
	 */
	public static List<ROI> splitROI(final ROI roi) {
		if (!(roi instanceof AreaROI || roi instanceof GeometryROI)) {
			return Collections.singletonList(roi);
		}
		
		var geometry = roi.getGeometry();
		var list = new ArrayList<ROI>();
		var plane = ImagePlane.getPlane(roi);
		for (int i = 0; i < geometry.getNumGeometries(); i++) {
			list.add(GeometryTools.convertGeometryToROI(geometry.getGeometryN(i), plane));
		}
		return list;
	}
	

	/**
	 * Split Area into PolygonROIs for the exterior and the holes.
	 * <p>
	 * The first array returned gives the <i>holes</i> and the second the positive regions (admittedly, it might have 
	 * been more logical the other way around).
	 * 
	 * <pre>
	 * {@code
	 * var polygons = splitAreaToPolygons(area, -1, 0, 0);
	 * var holes = polygons[0];
	 * var regions = polygons[1];
	 * }
	 * </pre>
	 * 
	 * @param area
	 * @param c
	 * @param z
	 * @param t
	 * @return
	 */
	public static PolygonROI[][] splitAreaToPolygons(final Area area, int c, int z, int t) {

		Map<Boolean, List<PolygonROI>> map = new HashMap<>();
		map.put(Boolean.TRUE, new ArrayList<>());
		map.put(Boolean.FALSE, new ArrayList<>());

		PathIterator iter = area.getPathIterator(null, 0.5);


		List<Point2> points = new ArrayList<>();


		double areaTempSigned = 0;
		double areaCached = 0;

		double[] seg = new double[6];
		double startX = Double.NaN, startY = Double.NaN;
		double x0 = 0, y0 = 0, x1 = 0, y1 = 0;
		boolean closed = false;
		while (!iter.isDone()) {
			switch(iter.currentSegment(seg)) {
			case PathIterator.SEG_MOVETO:
				// Log starting positions - need them again for closing the path
				startX = seg[0];
				startY = seg[1];
				x0 = startX;
				y0 = startY;
				iter.next();
				areaCached += areaTempSigned;
				areaTempSigned = 0;
				points.clear();
				points.add(new Point2(startX, startY));
				closed = false;
				continue;
			case PathIterator.SEG_CLOSE:
				x1 = startX;
				y1 = startY;
				closed = true;
				break;
			case PathIterator.SEG_LINETO:
				x1 = seg[0];
				y1 = seg[1];
				points.add(new Point2(x1, y1));
				closed = false;
				break;
			default:
				// Shouldn't happen because of flattened PathIterator
				throw new RuntimeException("Invalid area computation!");
			};
			areaTempSigned += 0.5 * (x0 * y1 - x1 * y0);
			// Add polygon if it has just been closed
			if (closed) {
				if (areaTempSigned < 0)
					map.get(Boolean.FALSE).add(new PolygonROI(points));
				else if (areaTempSigned > 0)
					map.get(Boolean.TRUE).add(new PolygonROI(points));
				// Zero indicates the shape is empty...
			}
			// Update the coordinates
			x0 = x1;
			y0 = y1;
			iter.next();
		}
		// TODO: Decide which is positive and which is negative
		areaCached += areaTempSigned;
		PolygonROI[][] polyOutput = new PolygonROI[2][];
		if (areaCached < 0) {
			polyOutput[0] = map.get(Boolean.TRUE).toArray(new PolygonROI[0]);
			polyOutput[1] = map.get(Boolean.FALSE).toArray(new PolygonROI[0]);
		} else {
			polyOutput[0] = map.get(Boolean.FALSE).toArray(new PolygonROI[0]);
			polyOutput[1] = map.get(Boolean.TRUE).toArray(new PolygonROI[0]);			
		}
		//		areaCached = Math.abs(areaCached + areaTempSigned);

		return polyOutput;
	}

	static PolygonROI[][] splitAreaToPolygons(final AreaROI pathROI) {
		return splitAreaToPolygons(getArea(pathROI), pathROI.getC(), pathROI.getZ(), pathROI.getT());
	}

	/**
	 * Test if two PathROIs share the same channel, z-slice &amp; time-point
	 * 
	 * @param roi1
	 * @param roi2
	 * @return
	 */
	static boolean sameImagePlane(ROI roi1, ROI roi2) {
//		if (roi1.getC() != roi2.getC())
//			logger.info("Channels differ");
//		if (roi1.getT() != roi2.getT())
//			logger.info("Timepoints differ");
//		if (roi1.getZ() != roi2.getZ())
//			logger.info("Z-slices differ");
		return roi1.getC() == roi2.getC() && roi1.getT() == roi2.getT() && roi1.getZ() == roi2.getZ();
	}

	/**
	 * Returns true if pathROI is an area that contains x &amp; y somewhere within it.
	 * 
	 * @param pathROI
	 * @param x
	 * @param y
	 * @return
	 */
	public static boolean areaContains(final ROI pathROI, final double x, final double y) {
		return (pathROI instanceof PathArea) && ((PathArea)pathROI).contains(x, y);
	}

	static List<Point2> getLinearPathPoints(final Path2D path, final PathIterator iter) {
			List<Point2> points = new ArrayList<>();
			double[] seg = new double[6];
			while (!iter.isDone()) {
				switch(iter.currentSegment(seg)) {
				case PathIterator.SEG_MOVETO:
					// Fall through
				case PathIterator.SEG_LINETO:
					points.add(new Point2(seg[0], seg[1]));
					break;
				case PathIterator.SEG_CLOSE:
	//				// Add first point again
	//				if (!points.isEmpty())
	//					points.add(points.get(0));
					break;
				default:
					throw new RuntimeException("Invalid polygon " + path + " - only line connections are allowed");
				};
				iter.next();
			}
			return points;
		}

	static List<Vertices> getVertices(final Shape shape) {
			Path2D path = shape instanceof Path2D ? (Path2D)shape : new Path2D.Float(shape);
			PathIterator iter = path.getPathIterator(null, 0.5);
			List<Vertices> verticesList = new ArrayList<>();
			MutableVertices vertices = null;
			double[] seg = new double[6];
			while (!iter.isDone()) {
				switch(iter.currentSegment(seg)) {
				case PathIterator.SEG_MOVETO:
					vertices = new DefaultMutableVertices(new DefaultVertices());
					// Fall through
				case PathIterator.SEG_LINETO:
					vertices.add(seg[0], seg[1]);
					break;
				case PathIterator.SEG_CLOSE:
	//				// Add first point again
					vertices.close();
					verticesList.add(vertices.getVertices());
					break;
				default:
					throw new RuntimeException("Invalid polygon " + path + " - only line connections are allowed");
				};
				iter.next();
			}
			return verticesList;
		}


//	/**
//	 * Dilate or erode a ROI using a circular structuring element.
//	 * 
//	 * @param roi The ROI to dilate or erode.
//	 * @param radius The radius of the structuring element to use.  If positive this will be a dilation, if negative an erosion.
//	 * @return
//	 */
//	public static PathShape roiMorphology(final ROI roi, final double radius) {
//		// Much faster to use JTS...
//		return (PathShape)ConverterJTS.convertGeometryToROI(roi.getGeometry().buffer(radius), ImagePlane.getPlane(roi));
////		return getShapeROI(shapeMorphology(getShape(roi), radius), roi.getC(), roi.getZ(), roi.getT());
//	}
	
	
	
//	/**
//	 * Query if a ROI represents a rectangle.
//	 * <p>
//	 * Note that this checks the representation of the ROI; it does <i>not<i/>
//	 * check for polygons or other shapes that happen to define a rectangular shape.
//	 * @param roi
//	 * @return
//	 */
//	public static boolean isSimpleRectangle(final ROI roi) {
//		return roi instanceof RectangleROI;
//	}
//
//	/**
//	 * Query if a ROI represents an ellipse.
//	 * @param roi
//	 * @return
//	 */
//	public static boolean isSimpleEllipse(final ROI roi) {
//		return roi instanceof EllipseROI;
//	}
//
//	/**
//	 * Query if a ROI represents a simple closed polygon, without holes.
//	 * <p>
//	 * Note that this checks the representation of the ROI; it does <i>not<i/>
//	 * check for more complex ROIs that may happen to define a simple polygonal shapes.
//	 * @param roi
//	 * @return
//	 */
//	public static boolean isSimplePolygon(final ROI roi) {
//		return roi instanceof PolygonROI;
//	}
//
//	/**
//	 * Query if a ROI represents a simple (open) polyline.
//	 * <p>
//	 * Note that this checks the representation of the ROI; it returns false for (straight) Line ROIs.
//	 * @param roi
//	 * @return
//	 */
//	public static boolean isSimplePolyline(final ROI roi) {
//		return roi instanceof PolylineROI;
//	}

}