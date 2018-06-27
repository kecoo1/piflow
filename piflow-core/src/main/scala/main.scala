package cn.piflow

import java.util.concurrent.{CountDownLatch, TimeUnit}

import cn.piflow.util.{IdGenerator, Logging}
import org.apache.spark.sql._

import scala.collection.mutable.{ArrayBuffer, Map => MMap}

trait JobInputStream {
  def isEmpty(): Boolean;

  def read(): DataFrame;

  def read(bundle: String): DataFrame;
}

trait JobOutputStream {
  def makeCheckPoint(pec: JobContext): Unit;

  def write(data: DataFrame);

  def write(bundle: String, data: DataFrame);

  def sendError();
}

trait StopJob {
  def jid(): String;

  def getStopName(): String;

  def getStop(): Stop;
}

trait JobContext extends Context {
  def getStopJob(): StopJob;

  def getInputStream(): JobInputStream;

  def getOutputStream(): JobOutputStream;

  def getProcessContext(): ProcessContext;
}

trait Stop {
  def initialize(ctx: ProcessContext): Unit;

  def perform(in: JobInputStream, out: JobOutputStream, pec: JobContext): Unit;
}

trait Flow {
  def getStopNames(): Seq[String];

  def hasCheckPoint(processName: String): Boolean;

  def getStop(name: String): Stop;

  def analyze(): AnalyzedFlowGraph;

  def show(): Unit;
}

trait Path {
  def toEdges(): Seq[Edge];

  def addEdge(edge: Edge): Path;

  def to(stopTo: String, bundleOut: String = "", bundleIn: String = ""): Path;
}

class PathImpl() extends Path {
  val edges = ArrayBuffer[Edge]();

  override def toEdges(): Seq[Edge] = edges.toSeq;

  override def addEdge(edge: Edge): Path = {
    edges += edge;
    this;
  }

  override def to(stopTo: String, bundleOut: String, bundleIn: String): Path = {
    edges += new Edge(edges.last.stopTo, stopTo, bundleOut, bundleIn);
    this;
  }
}

case class Edge(stopFrom: String, stopTo: String, bundleOut: String, bundleIn: String) {
  override def toString() = {
    s"[$stopFrom]-($bundleOut)-($bundleIn)-[$stopTo]";
  }
}

object Path {

  trait PathHead {
    def to(stopTo: String, bundleOut: String = "", bundleIn: String = ""): Path;
  }

  def from(stopFrom: String): PathHead = {
    new PathHead() {
      override def to(processTo: String, bundleOut: String, bundleIn: String): Path = {
        val path = new PathImpl();
        path.addEdge(new Edge(stopFrom, processTo, bundleOut, bundleIn));
        path;
      }
    };
  }

  def of(path: (Any, String)): Path = {
    val pi = new PathImpl();

    def _addEdges(path: (Any, String)): Unit = {
      val value1 = path._1;

      //String->String
      if (value1.isInstanceOf[String]) {
        pi.addEdge(new Edge(value1.asInstanceOf[String], path._2, "", ""));
      }

      //(String->String)->String
      else if (value1.isInstanceOf[(Any, String)]) {
        val tuple = value1.asInstanceOf[(Any, String)];
        _addEdges(tuple);
        pi.addEdge(new Edge(tuple._2, path._2, "", ""));
      }

      else {
        throw new InvalidPathException(value1);
      }
    }

    _addEdges(path);
    pi;
  }
}

class FlowImpl extends Flow {
  val edges = ArrayBuffer[Edge]();
  val stops = MMap[String, Stop]();
  val checkpoints = ArrayBuffer[String]();

  def addStop(name: String, process: Stop) = {
    stops(name) = process;
    this;
  };

  override def show(): Unit = {
    edges.foreach { arrow =>
      println(arrow.toString());
    }
  }

  def addCheckPoint(processName: String): Unit = {
    checkpoints += processName;
  }

  override def hasCheckPoint(processName: String): Boolean = {
    checkpoints.contains(processName);
  }

  override def getStop(name: String) = stops(name);

  override def getStopNames(): Seq[String] = stops.map(_._1).toSeq;

  def addPath(path: Path): Flow = {
    edges ++= path.toEdges();
    this;
  }

  override def analyze(): AnalyzedFlowGraph =
    new AnalyzedFlowGraph() {
      val incomingEdges = MMap[String, ArrayBuffer[Edge]]();
      val outgoingEdges = MMap[String, ArrayBuffer[Edge]]();

      edges.foreach { edge =>
        incomingEdges.getOrElseUpdate(edge.stopTo, ArrayBuffer[Edge]()) += edge;
        outgoingEdges.getOrElseUpdate(edge.stopFrom, ArrayBuffer[Edge]()) += edge;
      }

      private def _visitProcess[T](processName: String, op: (String, Map[Edge, T]) => T, visited: MMap[String, T]): T = {
        if (!visited.contains(processName)) {
          //executes dependent processes
          val inputs =
            if (incomingEdges.contains(processName)) {
              //all incoming edges
              val edges = incomingEdges(processName);
              edges.map { edge =>
                edge ->
                  _visitProcess(edge.stopFrom, op, visited);
              }.toMap
            }
            else {
              Map[Edge, T]();
            }

          val ret = op(processName, inputs);
          visited(processName) = ret;
          ret;
        }
        else {
          visited(processName);
        }
      }

      override def visit[T](op: (String, Map[Edge, T]) => T): Unit = {
        val ends = stops.keys.filterNot(outgoingEdges.contains(_));
        val visited = MMap[String, T]();
        ends.foreach {
          _visitProcess(_, op, visited);
        }
      }
    }
}

trait AnalyzedFlowGraph {
  def visit[T](op: (String, Map[Edge, T]) => T): Unit;
}

trait Runner {
  def bind(key: String, value: Any): Runner;

  def start(flow: Flow): Process;
}

object Runner {
  def create(): Runner = new Runner() {
    val ctx = new CascadeContext();

    override def bind(key: String, value: Any): this.type = {
      ctx.put(key, value);
      this;
    }

    override def start(flow: Flow): Process = {
      new ProcessImpl(flow, ctx, this);
    }
  }
}

trait Process {
  def addListener(listener: FlowExecutionListener);

  def pid(): String;

  def awaitTermination();

  def awaitTermination(timeout: Long, unit: TimeUnit);

  def getFlow(): Flow;

  def fork(child: Flow): Process;

  def stop(): Unit;
}

trait ProcessContext extends Context {
  def getFlow(): Flow;

  def getProcess(): Process;
}

class JobInputStreamImpl() extends JobInputStream {
  //only returns DataFrame on calling read()
  val inputs = MMap[String, () => DataFrame]();

  override def isEmpty(): Boolean = inputs.isEmpty;

  def attach(inputs: Map[Edge, JobOutputStreamImpl]) = {
    this.inputs ++= inputs.filter(x => x._2.contains(x._1.bundleOut))
      .map(x => (x._1.bundleIn, x._2.getDataFrame(x._1.bundleOut)));
  };

  override def read(): DataFrame = {
    if (inputs.isEmpty)
      throw new NoInputAvailableException();

    read(inputs.head._1);
  };

  override def read(bundle: String): DataFrame = {
    inputs(bundle)();
  }
}

class JobOutputStreamImpl() extends JobOutputStream with Logging {
  override def makeCheckPoint(pec: JobContext) {
    mapDataFrame.foreach(en => {
      val path = pec.get("checkpoint.path").asInstanceOf[String].stripSuffix("/") + "/" + pec.getProcessContext().getProcess().pid() + "/" + pec.getStopJob().jid();
      logger.debug(s"writing data on checkpoint: $path");
      en._2.apply().write.parquet(path);
      mapDataFrame(en._1) = () => {
        logger.debug(s"loading data from checkpoint: $path");
        pec.get[SparkSession].read.parquet(path)
      };
    })
  }

  val mapDataFrame = MMap[String, () => DataFrame]();

  override def write(data: DataFrame): Unit = write("", data);

  override def sendError(): Unit = ???

  override def write(bundle: String, data: DataFrame): Unit = {
    mapDataFrame(bundle) = () => data;
  }

  def contains(bundle: String) = mapDataFrame.contains(bundle);

  def getDataFrame(bundle: String) = mapDataFrame(bundle);
}

class ProcessImpl(flow: Flow, runnerContext: Context, runner: Runner, parentProcess: Option[Process] = None)
  extends Process with Logging {

  val id = "process_" + IdGenerator.uuid() + "_" + IdGenerator.nextId[Process];
  val executionString = "" + id + parentProcess.map("(parent=" + _.toString + ")").getOrElse("");

  logger.debug(s"create process: $this, flow: $flow");
  flow.show();

  val listeners = ArrayBuffer[FlowExecutionListener](new FlowExecutionLogger());
  val process = this;
  val processContext = createContext(runnerContext);
  val latch = new CountDownLatch(1);
  var running = false;

  val workerThread = new Thread(new Runnable() {
    def perform() {
      //initialize all processes
      //initialize process context
      val jobs = MMap[String, StopJobImpl]();
      flow.getStopNames().foreach { stopName =>
        val stop = flow.getStop(stopName);
        stop.initialize(processContext);

        val pe = new StopJobImpl(stopName, stop, processContext);
        jobs(stopName) = pe;
        listeners.foreach(_.onJobInitialized(pe.getContext()));
      }

      val analyzed = flow.analyze();

      //runs processes
      analyzed.visit[JobOutputStreamImpl]((stopName: String, inputs: Map[Edge, JobOutputStreamImpl]) => {
        val pe = jobs(stopName);
        var outputs: JobOutputStreamImpl = null;
        try {
          listeners.foreach(_.onJobStarted(pe.getContext()));
          outputs = pe.perform(inputs);
          listeners.foreach(_.onJobCompleted(pe.getContext()));

          //is a checkpoint?
          if (flow.hasCheckPoint(stopName)) {
            //store dataset
            outputs.makeCheckPoint(pe.getContext());
          }
        }
        catch {
          case e: Throwable =>
            listeners.foreach(_.onJobFailed(pe.getContext()));
            throw e;
        }

        outputs;
      }
      );
    }

    override def run(): Unit = {
      running = true;

      //onFlowStarted
      listeners.foreach(_.onProcessStarted(processContext));
      try {
        perform();
        //onFlowCompleted
        listeners.foreach(_.onProcessCompleted(processContext));
      }
      //onFlowFailed
      catch {
        case e: Throwable =>
          listeners.foreach(_.onProcessFailed(processContext));
          throw e;
      }
      finally {
        latch.countDown();
        running = false;
      }
    }
  });

  //IMPORTANT: start thread
  workerThread.start();

  override def addListener(listener: FlowExecutionListener): Unit =
    listeners += listener;

  override def toString(): String = executionString;

  override def awaitTermination(): Unit = {
    latch.await();
  }

  override def awaitTermination(timeout: Long, unit: TimeUnit): Unit = {
    latch.await(timeout, unit);
    if (running)
      stop();
  }

  override def pid(): String = id;

  override def getFlow(): Flow = flow;

  private def createContext(runnerContext: Context): ProcessContext = {
    new CascadeContext(runnerContext) with ProcessContext {
      override def getFlow(): Flow = flow;

      override def getProcess(): Process = process;
    };
  }

  override def fork(child: Flow): Process = {
    //add flow process stack
    val process = new ProcessImpl(child, runnerContext, runner, Some(this));
    listeners.foreach(_.onProcessForked(processContext, process.processContext));
    process;
  }

  //TODO: stopSparkJob()
  override def stop(): Unit = {
    if (!running)
      throw new ProcessNotRunningException(this);

    workerThread.interrupt();
    listeners.foreach(_.onProcessAborted(processContext));
    latch.countDown();
  }
}

class JobContextImpl(job: StopJob, processContext: ProcessContext)
  extends CascadeContext(processContext)
    with JobContext
    with Logging {
  val is: JobInputStreamImpl = new JobInputStreamImpl();

  val os = new JobOutputStreamImpl();

  def getStopJob() = job;

  def getInputStream(): JobInputStream = is;

  def getOutputStream(): JobOutputStream = os;

  override def getProcessContext(): ProcessContext = processContext;
}

class StopJobImpl(stopName: String, stop: Stop, processContext: ProcessContext)
  extends StopJob with Logging {
  val id = "job_" + IdGenerator.nextId[StopJob];
  val pec = new JobContextImpl(this, processContext);

  override def jid(): String = id;

  def getContext() = pec;

  def perform(inputs: Map[Edge, JobOutputStreamImpl]): JobOutputStreamImpl = {
    pec.getInputStream().asInstanceOf[JobInputStreamImpl].attach(inputs);
    stop.perform(pec.getInputStream(), pec.getOutputStream(), pec);
    pec.getOutputStream().asInstanceOf[JobOutputStreamImpl];
  }

  override def getStopName(): String = stopName;

  override def getStop(): Stop = stop;
}

trait Context {
  def get(key: String): Any;

  def get(key: String, defaultValue: Any): Any;

  def get[T]()(implicit m: Manifest[T]): T = {
    get(m.runtimeClass.getName).asInstanceOf[T];
  }

  def put(key: String, value: Any): this.type;

  def put[T](value: T)(implicit m: Manifest[T]): this.type =
    put(m.runtimeClass.getName, value);
}

class CascadeContext(parent: Context = null) extends Context with Logging {
  val map = MMap[String, Any]();

  override def get(key: String): Any = internalGet(key,
    () => throw new ParameterNotSetException(key));

  override def get(key: String, defaultValue: Any): Any = internalGet(key,
    () => {
      logger.warn(s"value of '$key' not set, using default: $defaultValue");
      defaultValue;
    });

  def internalGet(key: String, op: () => Unit): Any = {
    if (map.contains(key)) {
      map(key);
    }
    else {
      if (parent != null)
        parent.get(key);
      else
        op();
    }
  };

  override def put(key: String, value: Any): this.type = {
    map(key) = value;
    this;
  }
}

trait FlowExecutionListener {
  def onProcessStarted(ctx: ProcessContext);

  def onProcessForked(ctx: ProcessContext, child: ProcessContext);

  def onProcessCompleted(ctx: ProcessContext);

  def onProcessFailed(ctx: ProcessContext);

  def onProcessAborted(ctx: ProcessContext);

  def onJobInitialized(ctx: JobContext);

  def onJobStarted(ctx: JobContext);

  def onJobCompleted(ctx: JobContext);

  def onJobFailed(ctx: JobContext);
}

class FlowExecutionLogger extends FlowExecutionListener with Logging {
  override def onProcessStarted(ctx: ProcessContext): Unit = {
    val pid = ctx.getProcess().pid();
    val flowName = ctx.getFlow().toString;
    logger.debug(s"process started: $pid, flow: $flowName");
  };

  override def onJobStarted(ctx: JobContext): Unit = {
    val jid = ctx.getStopJob().jid();
    val stopName = ctx.getStopJob().getStopName();
    logger.debug(s"job started: $jid, stop: $stopName");
  };

  override def onJobFailed(ctx: JobContext): Unit = {
    val stopName = ctx.getStopJob().getStopName();
    logger.debug(s"job failed: $stopName");
  };

  override def onJobInitialized(ctx: JobContext): Unit = {
    val stopName = ctx.getStopJob().getStopName();
    logger.debug(s"job initialized: $stopName");
  };

  override def onProcessCompleted(ctx: ProcessContext): Unit = {
    val pid = ctx.getProcess().pid();
    logger.debug(s"process completed: $pid");
  };

  override def onJobCompleted(ctx: JobContext): Unit = {
    val stopName = ctx.getStopJob().getStopName();
    logger.debug(s"job completed: $stopName");
  };

  override def onProcessFailed(ctx: ProcessContext): Unit = {
    val pid = ctx.getProcess().pid();
    logger.debug(s"process failed: $pid");
  }

  override def onProcessAborted(ctx: ProcessContext): Unit = {
    val pid = ctx.getProcess().pid();
    logger.debug(s"process aborted: $pid");
  }

  override def onProcessForked(ctx: ProcessContext, child: ProcessContext): Unit = {
    val pid = ctx.getProcess().pid();
    val cid = child.getProcess().pid();
    logger.debug(s"process forked: $pid, child flow execution: $cid");
  }
}

class FlowException(msg: String = null, cause: Throwable = null) extends RuntimeException(msg, cause) {

}

class NoInputAvailableException extends FlowException() {

}

class ParameterNotSetException(key: String) extends FlowException(s"parameter not set: $key") {

}

//sub flow
class FlowAsStop(flow: Flow) extends Stop {
  override def initialize(ctx: ProcessContext): Unit = {
  }

  override def perform(in: JobInputStream, out: JobOutputStream, pec: JobContext): Unit = {
    pec.getProcessContext().getProcess().fork(flow).awaitTermination();
  }
}

class ProcessNotRunningException(process: Process) extends FlowException() {

}

class InvalidPathException(head: Any) extends FlowException() {

}