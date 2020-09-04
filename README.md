# akkaPayments
Простое приложение на технологии Akka(Core, Streams), которое считывает из определённых по маске файлов переводы с одного счёта на другой, и производит их.

## Пример использования

В конфигурационном файле application.conf (src/main/resources/application.conf), в дереве akka, указать следующие свойства:
```
app {
  catalog = "D:\\payments" # папка с файлами переводов
  maskFile = "file[0-9]+.txt" # маска файлов
  maskPayment = "([A-Za-z0-9]+) (->) ([A-Za-z0-9]+) (:) ([0-9]+)" # маска перевода
  balance = 500 #  Начальный баланс для всех счетов
  # остальные свойства
}
```

Пример перевода в считываемых файлах (по маске в конфигурационном файле, определённом выше):
```
a1 -> a2 : 100
```

## Части приложения
Приложение состоит из следующих акторов:

- Актор **PaymentsReader**, вычитывает из указанного в конфигурации(aplication.conf) каталога файлы по маске имени, так же указанной в конфигурации;
- Актор **PaymentChecker**, который принимает сообщение вида _CheckPayment(payment: String)_ от актора **PaymentsReader** по каждой строке платежа, и, в случае не удовлетворения маске платежа(1), откидывает сообщение в актор **LogIncorrectPayment**, а в случае успеха создаёт по одному актору на каждого из участников платежа и отправляет им сообщение _Payment(sign: PaymentSign, value: Long, participant: ActorRef)_;
- Актор **LogIncorrectPayment**, который принимает сообщение с текстом ошибки некорректного перевода и пишет в лог;
- Актор **PaymentParticipant**, который принимает сообщение _Payment_ в котором определяется знак платежа(+ или -), производится действие с балансом и значением value, логируется результат. В случае если происходит вычитание такого значения, что баланса не хватает, происходит логирование нехватки баланса, баланс остается прежним, и актор отсылает сообщение _StopPayment_ на актор participant. В случае если на данный актор пришло такое сообщение, то актор "отменяет" платёж, логируя данное действие. 
