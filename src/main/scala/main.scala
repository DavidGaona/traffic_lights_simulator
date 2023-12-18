import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, PoisonPill, Props, Timers}

import scala.collection.immutable.Map as ScalaMap
import scala.collection.mutable.Map
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.util.Random

case class traffic_lights()

// Global control
case object StopGenerator

case object StartGenerator

// Timer

// Messages
case object PeriodicTick

case object StartTimer

case object StopTimer

class TimerActor(replyTo: ActorRef, message: Any, delay: FiniteDuration) extends Actor with Timers {
    val timerKey: String = self.path.name

    override def preStart(): Unit = {
        super.preStart()
        timers.startTimerWithFixedDelay(timerKey, PeriodicTick, delay)
    }

    override def receive: Receive = {
        case StartTimer =>
            timers.startTimerWithFixedDelay(timerKey, PeriodicTick, delay)

        case StopTimer =>
            timers.cancel(timerKey)

        case PeriodicTick =>
            replyTo ! message
    }
}

case object SayHello

case object TestS

case object TestR

class TestActor extends Actor {
    val timer: ActorRef = context.actorOf(
        Props(new TimerActor(self, SayHello, 1.seconds)),
        name = s"Timer_of_${self.path.name}"
    )

    override def receive: Receive = {
        case SayHello =>
            println("Hello!")

        case TestS =>
            timer ! StopTimer

        case TestR =>
            timer ! StartTimer
    }
}

// Generator

// Messages
case object CanGenerate

case object GenerateVehicle

// Actor

class Generator(sensor: ActorRef, group: ActorRef) extends Actor {
    val frequency: FiniteDuration = 5000.milliseconds
    val timer: ActorRef = context.actorOf(
        Props(new TimerActor(group, CanGenerate, frequency)),
        name = s"Timer_of_${self.path.name}"
    )

    def receive: Receive = generating

    def generating: Receive = {
        case StopGenerator =>
            timer ! StopTimer
            context.become(stopped)

        case RespondToGenerator =>
            sensor ! GenerateVehicle
    }

    def stopped: Receive = {
        case StartGenerator =>
            timer ! StartTimer
            context.unbecome()
    }
}

// Sensor

// Messages
case object NewVehicle

case class VehicleLeft(vehicle: ActorRef)

case object ScanCurrentPositions

// Actor
class Sensor(group: ActorRef, x1: Double, y1: Double, x2: Double, y2: Double) extends Actor {
    val frequency: FiniteDuration = 2000.milliseconds
    val timer: ActorRef = context.actorOf(
        Props(new TimerActor(group, ScanCurrentPositions, frequency)),
        name = s"Timer_of_${self.path.name}"
    )

    def receive: Receive = {
        case GenerateVehicle =>
            group ! NewVehicle
            println(s"New vehicle entered ${group.path.name}")

        case SendCurrentPosition(x_coords, y_coords) =>
            val vehicle = sender()
            if (!isPointInsideRectangle(x_coords, y_coords, x1, y1, x2, y2)) {
                group ! VehicleLeft(vehicle)
            }
    }

    private def isPointInsideRectangle(x: Double, y: Double, x1: Double, y1: Double, x2: Double, y2: Double): Boolean = {
        x >= x1 && x <= x2 && y >= y1 && y <= y2
    }
}

// Vehicle

// Messages
case class SendCurrentPosition(xCords: Double, yCords: Double)

case object UpdatePosition

// Actor
class Vehicle(var xCords: Double, var yCords: Double) extends Actor {
    val frequency: FiniteDuration = 1000.milliseconds
    var currentSpeed: Double = 0.0
    val timer: ActorRef = context.actorOf(
        Props(new TimerActor(self, UpdatePosition, frequency)),
        name = s"Timer_of_${self.path.name}"
    )
    timer ! StopTimer

    def receive: Receive = {
        case Move(speed) if speed == 0.0 =>
            timer ! StopTimer
            currentSpeed = speed
            context.become(stopped)
        case Move(speed) if speed > 0.0 =>
            timer ! StartTimer
            currentSpeed = speed
        case UpdatePosition =>
            xCords += currentSpeed
            yCords += currentSpeed
            println(s"${self.path.name} moved to pos ($xCords, $yCords)")
        case AlertVehicle(sensor: ActorRef) =>
            sensor ! SendCurrentPosition(xCords, yCords)
        case _ =>
    }

    def stopped: Receive = {
        case Move(speed) if speed > 0.0 =>
            timer ! StartTimer
            currentSpeed = speed
            context.unbecome()
        case _ =>
    }
}

// Group

// Messages
case class Move(speed: Double)

case class AlertVehicle(sensor: ActorRef)

case class SendCurrentCapacity(currentCapacity: Int)

case object RespondToGenerator

// Actor

class Group extends Actor {
    var vehicles: Vector[ActorRef] = Vector.empty
    var maxCapacity: Int = 50
    var maxSpeed: Double = 40.0
    var curSpeed: Double = 0.0
    var vehicleCounter: Int = 0
    val sensor: ActorRef = context.actorOf(
        Props(new Sensor(self, 0.0, 0.0, 450.0, 450.0)),
        name = s"Sensor_of_${self.path.name}"
    )
    val generator: ActorRef = context.actorOf(
        Props(new Generator(sensor, self)),
        name = s"Generator_of_${self.path.name}"
    )

    def receive: Receive = {
        case NewVehicle =>
            vehicleCounter += 1
            val newVehicle = context.actorOf(
                Props(new Vehicle(0, 0)),
                s"Vehicle${vehicleCounter}_of_${self.path.name}"
            )
            newVehicle ! Move(curSpeed)
            vehicles = vehicles :+ newVehicle
            println(s"New vehicle at ${self.path.name} total vehicles ${vehicles.size}")

        case VehicleLeft(vehicle) =>
            vehicles = vehicles.filterNot(_ == vehicle)
            vehicle ! PoisonPill

        case CanGenerate if vehicles.size < maxCapacity =>
            generator ! RespondToGenerator

        case ScanCurrentPositions =>
            vehicles.foreach(vehicle => vehicle ! AlertVehicle(sensor))

        case ColorChange(Red) =>
            println(s"Stopping vehicles ${vehicles}")
            vehicles.foreach(vehicle => vehicle ! Move(0.0))
            curSpeed = 0.0

        case ColorChange(Green) =>
            vehicles.foreach(vehicle => vehicle ! Move(maxSpeed))
            curSpeed = maxSpeed

        case ColorChange(YellowRed) =>
            vehicles.foreach(vehicle => vehicle ! Move(maxSpeed / 2))
            curSpeed = maxSpeed / 2


    }
}


// Controller

// Messages
case object GetCapacity

case class ChangePlan(newPlan: ScalaMap[ActorRef, (TrafficLightState, Double, Double)], newCycleTime: Double)

// Actor
class Controller(numberOfGroups: Int, numberOfTrafficLights: Int) extends Actor {
    val frequency: FiniteDuration = 2000.milliseconds
    val MinMaxStateTimes: ScalaMap[TrafficLightState, (Double, Double)] = ScalaMap(
        Red -> (7, 120),
        YellowRed -> (3, 6),
        Green -> (7, 120)
    )
    var groups: Vector[ActorRef] = Vector.empty
    var trafficLights: Vector[ActorRef] = Vector.empty
    val plan: ActorRef = context.actorOf(
        Props(new Plan),
        name = s"Plan_of_${self.path.name}"
    )
    var initial_plan: ScalaMap[ActorRef, (TrafficLightState, Double, Double)] = ScalaMap.empty

    def receive: Receive = {
        case SendCurrentCapacity(capacity) =>
        // ToDo logic to update plan
        // plan ! ChangePlan(...)
    }

    override def preStart(): Unit = {
        val random = new Random
        for (i <- 1 to numberOfGroups) {
            groups = groups :+ context.actorOf(Props(new Group), name = s"group${i}")
        }

        for (i <- 1 to numberOfTrafficLights) {
            if (i > numberOfGroups){
                trafficLights = trafficLights :+ context.actorOf(
                    Props(new TrafficLight(groups(random.nextInt(numberOfGroups + 1)))),
                    name = s"Traffic_light${i}"
                )
            } else {
                trafficLights = trafficLights :+ context.actorOf(
                    Props(new TrafficLight(groups(i - 1))),
                    name = s"Traffic_light${i}"
                )
            }
        }
        initial_plan = initial_plan + (
            trafficLights(0) -> (Green, 0, 10),
            trafficLights(1) -> (Green, 13, 22),
            trafficLights(2) -> (Green, 25, 30),
            trafficLights(3) -> (Green, 33, 45)
        )
        plan ! ChangePlan(initial_plan, 45)

    }
}

// Plan

// Messages
case class ChangeLight(color: TrafficLightState)

// Actor
class Plan extends Actor {
    var trafficLights: ScalaMap[ActorRef, (TrafficLightState, Double, Double)] = ScalaMap.empty
    var cycleTime: Double = 0
    val frequency: FiniteDuration = 1000.milliseconds
    var currentTime: Double = 0.0
    val timer: ActorRef = context.actorOf(
        Props(new TimerActor(self, ChangeLight, frequency)),
        name = s"Timer_of_${self.path.name}"
    )

    def receive: Receive = {
        case ChangePlan(newPlan, newCycleTime) =>
            trafficLights = newPlan
            cycleTime = newCycleTime

        case ChangeLight =>
            if (currentTime > cycleTime) {
                currentTime = 0
            }
            for ((trafficLight, (state, start, end)) <- trafficLights) {
                if (isGreenLight(start, end))
                    trafficLight ! ChangeLight(state)
                else
                    trafficLight ! ChangeLight(Red)
            }
            currentTime += 1

    }

    def isGreenLight(lowerBound: Double, upperBound: Double): Boolean = {
        currentTime >= lowerBound && currentTime <= upperBound
    }


}


// Traffic Light
sealed trait TrafficLightState

case object Red extends TrafficLightState

case object YellowRed extends TrafficLightState

case object Green extends TrafficLightState

// Messages
case class ColorChange(color: TrafficLightState)

// Actor
class TrafficLight(group: ActorRef) extends Actor {
    var currentColor: TrafficLightState = Red

    def receive: Receive = {
        case ChangeLight(color) =>
            if (color != currentColor)
                currentColor = color
                group ! ColorChange(currentColor)
    }
}

object Main extends App {
    val system = ActorSystem("original")
    system.actorOf(Props(new Controller(4, 4)), name = s"Controller")
    //val testActor = system.actorOf(Props(new TestActor), name = s"test_actor")

    //Thread.sleep(5000)
    //testActor ! TestS
    //Thread.sleep(5000)
    //testActor ! TestR
//    var groups: Vector[ActorRef] = Vector.empty
//    for (i <- 1 to 1) {
//        groups = groups :+ system.actorOf(Props(new Group), name = s"group${i}")
//        println(groups(i - 1).path.name)
//    }
//
//
//    def delayedTaskGreen(): Unit = {
//        groups(0) ! ColorChange(Green)
//    }
//
//    def delayedTaskRed(): Unit = {
//        groups(0) ! ColorChange(Red)
//    }
//
//    Future {
//        Thread.sleep(5000)
//        delayedTaskGreen()
//    }
//
//    Future {
//        Thread.sleep(15000)
//        delayedTaskRed()
//    }
//
//    Future {
//        Thread.sleep(25000)
//        delayedTaskGreen()
//    }



    //system.terminate()
}
